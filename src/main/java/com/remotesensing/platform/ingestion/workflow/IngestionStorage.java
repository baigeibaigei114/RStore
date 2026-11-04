package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/** Private ingestion objects use the configured server endpoint and fixed server-generated keys. */
@Service
public class IngestionStorage {
    private final MinioProperties config;
    private final IngestionProperties limits;
    private final OkHttpClient sharedHttp = new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS)
            .readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).build();
    public IngestionStorage(MinioProperties config, IngestionProperties limits) { this.config=config; this.limits=limits; }
    public String bucket() {
        String name=limits.getBucketName()==null ? config.getBucketName()+"-ingestion" : limits.getBucketName();
        IngestionProblem.require(name.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") && !name.equals(config.getBucketName()),503,"SERVICE_UNAVAILABLE");
        return name;
    }
    public synchronized void ensurePrivate() {
        try {
            MinioClient client=client(30);
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket()).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket()).build());
            try {
                String policy=client.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket()).build());
                IngestionProblem.require(policy==null || policy.isBlank(),503,"SERVICE_UNAVAILABLE");
            } catch (io.minio.errors.ErrorResponseException error) {
                if (!"NoSuchBucketPolicy".equals(error.errorResponse().code())) throw error;
            }
        } catch (IngestionProblem error) { throw error; }
        catch (Exception error) { throw new IngestionProblem(503,"SERVICE_UNAVAILABLE"); }
    }
    private MinioClient client(long seconds) {
        long absoluteDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,seconds));
        OkHttpClient http = sharedHttp.newBuilder().callTimeout(Math.max(1,seconds),TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    // Multipart consists of several calls; they share ONE stage deadline.
                    chain.call().timeout().deadlineNanoTime(absoluteDeadline);
                    return chain.proceed(chain.request());
                }).build();
        return MinioClient.builder().endpoint(config.getEndpoint()).credentials(config.getAccessKey(),config.getSecretKey())
                .httpClient(http).build();
    }
    private String key(JsonNode object) {
        String key=object.path("server_key").asText();
        String role=object.path("role").asText();
        boolean valid=switch(role) {
            case "RAW" -> key.matches("ingestion/raw/[0-9a-f-]{36}/[0-9a-f-]{36}/product\\.zip");
            case "PREVIEW" -> key.matches("ingestion/preview/[0-9a-f-]{36}/[0-9a-f-]{36}/preview\\.png");
            case "TIFF","MANIFEST" -> key.equals("ingestion/artifacts/"+object.path("job_id").asText()+"/"+object.path("attempt_id").asText()+"/reflectance."+(role.equals("TIFF")?"tif":"json"))
                    && object.path("job_id").asText().matches("[0-9a-f-]{36}") && object.path("attempt_id").asText().matches("[0-9a-f-]{36}");
            default -> false;
        };
        IngestionProblem.require(bucket().equals(object.path("bucket").asText()) && valid,409,"INPUT_HASH_MISMATCH");
        return key;
    }
    public long size(JsonNode object,long seconds) {
        requireTime(seconds);
        try { return client(seconds).statObject(StatObjectArgs.builder().bucket(bucket()).object(key(object)).build()).size(); }
        catch(IngestionProblem error) {throw error;}
        catch(Exception error) {throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
    }
    public String download(JsonNode object,Path destination,long seconds,long maximum) {
        requireTime(seconds); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        try(var source=client(seconds).getObject(GetObjectArgs.builder().bucket(bucket()).object(key(object)).build());
            var target=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)) {
            var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long count=0;int n;
            while((n=source.read(buffer))>=0) {
                count+=n;IngestionProblem.require(count<=maximum && count<=object.path("actual_bytes").asLong(),409,"INPUT_HASH_MISMATCH");
                IngestionProblem.require(System.nanoTime()<deadline,503,"RESOURCE_LIMIT");target.write(buffer,0,n);digest.update(buffer,0,n);
            }
            String hash=java.util.HexFormat.of().formatHex(digest.digest());
            IngestionProblem.require(count==object.path("actual_bytes").asLong() && hash.equals(object.path("sha256").asText()),409,"INPUT_HASH_MISMATCH");return hash;
        } catch(IngestionProblem error) {throw error;}
        catch(Exception error) {throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
    }
    public void upload(JsonNode object, Path path, long remainingSeconds) {
        try {
            requireTime(remainingSeconds);
            MinioClient client=client(remainingSeconds);
            client.uploadObject(UploadObjectArgs.builder().bucket(bucket()).object(key(object))
                    .filename(path.toString()).contentType("application/zip").build());
            IngestionProblem.require(client.statObject(StatObjectArgs.builder().bucket(bucket()).object(key(object)).build()).size()==Files.size(path),409,"INPUT_HASH_MISMATCH");
        } catch (Exception error) { throw new IngestionProblem(503,"SERVICE_UNAVAILABLE"); }
    }
    private void requireTime(long remainingSeconds) { IngestionProblem.require(remainingSeconds>0,410,"UPLOAD_EXPIRED"); }
    @jakarta.annotation.PreDestroy
    public void close() { sharedHttp.dispatcher().executorService().shutdown(); sharedHttp.connectionPool().evictAll(); }
    public GetObjectResponse open(JsonNode object) {
        try { return client(1800).getObject(GetObjectArgs.builder().bucket(bucket()).object(key(object)).build()); }
        catch (Exception error) { throw new IngestionProblem(503,"SERVICE_UNAVAILABLE"); }
    }
    /** Called only after image/owner/permanent-reference authorization; does not widen legacy key access. */
    public FilePresignedUrlVO presignedTiff(JsonNode object) {
        IngestionProblem.require("TIFF".equals(object.path("role").asText()) && "AVAILABLE".equals(object.path("state").asText())
                && "PERMANENT_ASSET".equals(object.path("retention").asText()),409,"ASSET_CHANGED");
        String objectKey=key(object);
        String endpoint=config.getPublicEndpoint()==null || config.getPublicEndpoint().isBlank()?config.getEndpoint():config.getPublicEndpoint();
        try {
            MinioClient signer=MinioClient.builder().endpoint(endpoint).credentials(config.getAccessKey(),config.getSecretKey()).region(config.getRegion()).build();
            String url=signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.GET)
                    .bucket(object.path("bucket").asText()).object(objectKey).region(config.getRegion()).expiry(1800).build());
            return new FilePresignedUrlVO(objectKey,url,1800);
        } catch(Exception error) {throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
    }
    public byte[] preview(JsonNode object) {
        try (InputStream stream = client(30).getObject(GetObjectArgs.builder().bucket(bucket()).object(key(object)).build())) {
            byte[] bytes=stream.readNBytes(8*1024*1024+1);
            IngestionProblem.require(bytes.length<=8*1024*1024,400,"RESOURCE_LIMIT");
            return bytes;
        } catch (IngestionProblem error) { throw error; }
        catch (Exception error) { throw new IngestionProblem(503,"SERVICE_UNAVAILABLE"); }
    }
}
