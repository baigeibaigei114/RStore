package com.remotesensing.platform.ingestion.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.config.properties.GeoServerProperties;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService.Asset;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService.MapRequest;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import okhttp3.*;
import org.springframework.stereotype.Component;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Fixed server-owned endpoints, two identities, no redirects and bounded bodies/deadlines. */
@Component
public class IngestionGeoServerClient {
    private final GeoServerProperties config;
    private final IngestionProperties limits;
    private final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .writeTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    public IngestionGeoServerClient(GeoServerProperties config,IngestionProperties limits){this.config=config;this.limits=limits;}
    public boolean configured() {
        return limits.isEnabled() && limits.isPublicationEnabled() && limits.isMapBoundaryVerified()
                && present(config.getIngestionReadUsername()) && present(config.getIngestionReadPassword())
                && present(config.getIngestionPublishUsername()) && present(config.getIngestionPublishPassword())
                && config.getIngestionReadPassword().length()>=16 && config.getIngestionPublishPassword().length()>=16
                && !config.getIngestionReadPassword().equals(config.getIngestionPublishPassword())
                && !config.getIngestionReadUsername().equals(config.getIngestionPublishUsername());
    }
    private boolean present(String value){return value!=null && !value.isBlank();}
    private Request.Builder request(String path,boolean publisher) {
        String base=config.getUrl().replaceAll("/+$","");HttpUrl baseUrl=HttpUrl.parse(base),url=HttpUrl.parse(base+path);
        require(baseUrl!=null && baseUrl.username().isEmpty() && baseUrl.password().isEmpty() && baseUrl.query()==null && baseUrl.fragment()==null && url!=null,503,"SERVICE_UNAVAILABLE");
        String user=publisher?config.getIngestionPublishUsername():config.getIngestionReadUsername();
        String pass=publisher?config.getIngestionPublishPassword():config.getIngestionReadPassword();
        require(present(user) && present(pass),503,"SERVICE_UNAVAILABLE");
        return new Request.Builder().url(url).header("Authorization",Credentials.basic(user,pass)).header("Accept","application/json");
    }
    private Response send(Request request,Instant deadline) throws IOException {
        long ms=Duration.between(Instant.now(),deadline).toMillis();require(ms>0,503,"HARD_TIMEOUT");
        Call call=http.newBuilder().callTimeout(Math.min(ms,30000),TimeUnit.MILLISECONDS).build().newCall(request);
        call.timeout().deadlineNanoTime(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms));return call.execute();
    }
    private byte[] bounded(Response r,int max) throws IOException {
        require(r.body()!=null && (r.body().contentLength()<0 || r.body().contentLength()<=max),503,"RESOURCE_LIMIT");
        byte[] bytes=r.body().byteStream().readNBytes(max+1);require(bytes.length<=max,503,"RESOURCE_LIMIT");return bytes;
    }
    private byte[] get(String path,boolean publisher,Instant deadline,String accept,boolean missing) {
        try(Response r=send(request(path,publisher).header("Accept",accept).get().build(),deadline)){
            if(missing && r.code()==404)return null;
            require(r.isSuccessful(),503,"PUBLISH_FAILED");return bounded(r,1<<20);
        } catch(IngestionProblem error){throw error;}
        catch(IOException error){throw new IngestionProblem(503,"PUBLISH_UNCERTAIN");}
    }
    private org.w3c.dom.Document xml(byte[] bytes) {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        }catch(Exception error){throw new IngestionProblem(503,"PUBLISH_FAILED");}
    }
    /** Live precondition, never an automatic ACL rewrite on an existing deployment. */
    public void boundary(Instant deadline) {
        require(configured(),503,"SERVICE_UNAVAILABLE");
        var rules=xml(get("/rest/security/acl/layers.xml",true,deadline,"application/xml",false)).getElementsByTagName("rule");
        boolean read=false,write=false;
        for(int i=0;i<rules.getLength();i++){
            var node=(org.w3c.dom.Element)rules.item(i);String resource=node.getAttribute("resource"),roles=node.getTextContent().trim();
            if(resource.startsWith("ingest_private.")){
                if(resource.equals("ingest_private.*.r")){require(roles.equals("ROLE_INGEST_PROXY"),503,"SERVICE_UNAVAILABLE");read=true;}
                else if(resource.equals("ingest_private.*.w")){require(roles.equals("ROLE_ADMINISTRATOR"),503,"SERVICE_UNAVAILABLE");write=true;}
                else require(false,503,"SERVICE_UNAVAILABLE");
            }
        }
        require(read && write,503,"SERVICE_UNAVAILABLE");
        var mode=xml(get("/rest/security/acl/catalog.xml",true,deadline,"application/xml",false)).getElementsByTagName("mode");
        require(mode.getLength()==1 && mode.item(0).getTextContent().trim().equals("HIDE"),503,"SERVICE_UNAVAILABLE");
        String user=config.getIngestionReadUsername();require(user.matches("[A-Za-z0-9_-]{1,64}"),503,"SERVICE_UNAVAILABLE");
        var roles=xml(get("/rest/security/roles/user/"+user,true,deadline,"application/xml",false)).getElementsByTagName("role");
        require(roles.getLength()==1 && roles.item(0).getTextContent().trim().equals("ROLE_INGEST_PROXY"),503,"SERVICE_UNAVAILABLE");
        try(Response r=send(request("/rest/workspaces.json",false).build(),deadline)){
            require(r.code()==401 || r.code()==403,503,"SERVICE_UNAVAILABLE");
        }catch(IOException error){throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
    }
    private JsonNode json(String path,Instant deadline,boolean missing){
        byte[] bytes=get(path,true,deadline,"application/json",missing);if(bytes==null)return null;
        try{return JSON.readTree(bytes);}catch(IOException error){throw new IngestionProblem(503,"PUBLISH_FAILED");}
    }
    private String store(Asset a){return "/rest/workspaces/ingest_private/coveragestores/"+a.publication().path("store_name").asText();}
    private String layer(Asset a){return a.publication().path("layer_name").asText();}
    private static final String STYLE="/rest/workspaces/ingest_private/styles/s2-rgb-v1";
    private void checkStyle(JsonNode document,Instant deadline){
        require(document.path("style").path("filename").asText().equals("s2-rgb-v1.sld"),503,"PUBLISH_FAILED");
        // The style endpoint can serialize a parsed SLD. Check the actual configured file bytes.
        byte[] style=get("/rest/resource/workspaces/ingest_private/styles/s2-rgb-v1.sld",true,deadline,"*/*",false);
        require(sha(style).equals(sha(styleBytes())),503,"PUBLISH_FAILED");
    }
    public String sourceUrl(Asset a){
        String root=config.getSharedDataGeoServerDir();require(root!=null && root.startsWith("/") && !root.contains("..") && !root.contains("\\"),503,"SERVICE_UNAVAILABLE");
        return "file:"+root.replaceAll("/+$","")+"/ingestion/assets/"+a.publication().path("image_id").asLong()+"/raster.tif";
    }
    public void source(Asset a,Instant deadline){
        checkStore(a,json(store(a)+".json",deadline,false).path("coverageStore"));
        JsonNode l=layerResource(a,deadline);
        require(fixedStyle(l),503,"PUBLISH_FAILED");
        checkStyle(json(STYLE+".json",deadline,false),deadline);
    }
    private String ownership(Asset a){
        return "rs-ingestion:v1:"+uuid(a.publication(),"id")+":"+a.tiff().path("sha256").asText();
    }
    /** A missing description is legacy. Any explicit description must match this publication. */
    private boolean checkStore(Asset a,JsonNode s){
        require(s.path("name").asText().equals(a.publication().path("store_name").asText()) && s.path("workspace").path("name").asText().equals("ingest_private")
                && s.path("type").asText().equals("GeoTIFF") && s.path("enabled").asBoolean() && sourceUrl(a).equals(s.path("url").asText()),503,"PUBLISH_FAILED");
        JsonNode description=s.path("description");
        if(description.isMissingNode() || description.isNull() || (description.isTextual() && description.textValue().isEmpty()))return false;
        require(description.isTextual() && ownership(a).equals(description.textValue()),503,"PUBLISH_FAILED");
        return true;
    }
    private JsonNode layerResource(Asset a,Instant deadline){
        JsonNode c=json(store(a)+"/coverages/"+layer(a)+".json",deadline,false).path("coverage");
        require(c.path("name").asText().equals(layer(a)) && c.path("store").path("name").asText().equals("ingest_private:"+a.publication().path("store_name").asText())
                && c.path("srs").asText().equals(a.manifest().path("crs").asText()) && c.path("enabled").asBoolean(),503,"PUBLISH_FAILED");
        JsonNode l=json("/rest/layers/ingest_private:"+layer(a)+".json",deadline,false).path("layer");
        require(l.path("resource").path("name").asText().equals("ingest_private:"+layer(a)),503,"PUBLISH_FAILED");
        return l;
    }
    private boolean fixedStyle(JsonNode layer){
        JsonNode style=layer.path("defaultStyle");
        return "ingest_private:s2-rgb-v1".equals(style.path("name").asText())
                && "ingest_private".equals(style.path("workspace").asText());
    }
    public byte[] map(Asset a,MapRequest m,Instant deadline) {
        boundary(deadline);source(a,deadline);
        HttpUrl url=request("/ingest_private/wms",false).build().url().newBuilder()
                .addQueryParameter("service","WMS").addQueryParameter("version","1.1.1").addQueryParameter("request","GetMap")
                .addQueryParameter("layers","ingest_private:"+layer(a)).addQueryParameter("styles","ingest_private:s2-rgb-v1")
                .addQueryParameter("format","image/png").addQueryParameter("bbox",m.bbox()).addQueryParameter("srs",m.srs())
                .addQueryParameter("width",Integer.toString(m.width())).addQueryParameter("height",Integer.toString(m.height()))
                .addQueryParameter("transparent",Boolean.toString(m.transparent())).build();
        try(Response r=send(request("/ingest_private/wms",false).url(url).build(),deadline)){
            require(r.isSuccessful(),503,"PUBLISH_FAILED");byte[] bytes=bounded(r,32<<20);
            IngestionPngValidation.validate(bytes,r.header("Content-Type"),m.width(),m.height());return bytes;
        }catch(IngestionProblem error){throw error;}catch(IOException error){throw new IngestionProblem(503,"PUBLISH_UNCERTAIN");}
    }
    public record Coverage(Path path,long bytes) implements AutoCloseable {
        @Override public void close() throws IOException {Files.deleteIfExists(path);}
    }
    public Coverage coverage(Asset a,Instant deadline){
        boundary(deadline);source(a,deadline);Path file=null;
        HttpUrl url=request("/ingest_private/wcs",false).build().url().newBuilder().addQueryParameter("service","WCS")
                .addQueryParameter("version","2.0.1").addQueryParameter("request","GetCoverage")
                .addQueryParameter("coverageId","ingest_private:"+layer(a)).addQueryParameter("format","image/tiff").build();
        try(Response r=send(request("/ingest_private/wcs",false).url(url).build(),deadline)){
            String type=r.header("Content-Type","").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            require(r.isSuccessful() && Set.of("image/tiff","image/geotiff","application/geotiff").contains(type),503,"PUBLISH_FAILED");
            require(r.body()!=null && r.body().contentLength()<=(256L<<20),503,"RESOURCE_LIMIT");
            Path root=Path.of(limits.getTempDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(root);require(!Files.isSymbolicLink(root),503,"SERVICE_UNAVAILABLE");
            require(Files.getFileStore(root).getUsableSpace()>=(256L<<20)+limits.getDiskSafetyBytes(),503,"RESOURCE_LIMIT");
            file=Files.createTempFile(root,"coverage-",".tif");long count=0;
            try(InputStream in=r.body().byteStream();OutputStream out=Files.newOutputStream(file)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1){count+=n;require(count<=256L*1024*1024 && Instant.now().isBefore(deadline),503,"RESOURCE_LIMIT");out.write(buf,0,n);}
            }
            try(InputStream in=Files.newInputStream(file)){
                byte[] magic=in.readNBytes(4);require(Arrays.equals(magic,new byte[]{73,73,42,0}) || Arrays.equals(magic,new byte[]{77,77,0,42}),503,"PUBLISH_FAILED");
            }
            Coverage result=new Coverage(file,count);file=null;return result;
        }catch(IngestionProblem error){throw error;}catch(IOException error){throw new IngestionProblem(503,"PUBLISH_UNCERTAIN");}
        finally{if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){/* bounded response remains for local cleanup */}}
    }
    public byte[] styleBytes(){
        try(var stream=getClass().getResourceAsStream("/ingestion/s2-rgb-v1.sld")){
            require(stream!=null,503,"SERVICE_UNAVAILABLE");return stream.readAllBytes();
        }catch(IOException error){throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
    }
    private void mutate(String path,String method,byte[] bytes,String type,Instant deadline,Runnable beforeWrite){
        beforeWrite.run();
        RequestBody body=RequestBody.create(bytes,MediaType.parse(type));
        try(Response r=send(request(path,true).method(method,body).build(),deadline)){
            require(r.isSuccessful(),503,"PUBLISH_FAILED");
        }catch(IngestionProblem error){throw error;}catch(IOException error){throw new IngestionProblem(503,"PUBLISH_UNCERTAIN");}
    }
    public void publish(Asset a,Instant deadline,Runnable beforeWrite){
        boundary(deadline);
        JsonNode workspace=json("/rest/workspaces/ingest_private.json",deadline,true);
        JsonNode existing=workspace==null ? null : json(store(a)+".json",deadline,true);
        boolean owned=existing!=null && checkStore(a,existing.path("coverageStore"));
        if(workspace==null)mutate("/rest/workspaces","POST",utf8("{\"workspace\":{\"name\":\"ingest_private\"}}"),"application/json",deadline,beforeWrite);
        JsonNode style=json(STYLE+".json",deadline,true);
        if(style==null)mutate("/rest/workspaces/ingest_private/styles?name=s2-rgb-v1&raw=true","POST",styleBytes(),"application/vnd.ogc.sld+xml",deadline,beforeWrite);
        else checkStyle(style,deadline);
        // Bind ownership in the same request that first creates the store.
        String layerPath="/rest/layers/ingest_private:"+layer(a);
        JsonNode existingLayer=json(layerPath+".json",deadline,true);
        if(existing==null){
            require(existingLayer==null,503,"PUBLISH_FAILED");
            var document=JSON.createObjectNode();
            document.putObject("coverageStore").put("name",a.publication().path("store_name").asText())
                    .put("type","GeoTIFF").put("enabled",true).put("url",sourceUrl(a)).put("description",ownership(a))
                    .putObject("workspace").put("name","ingest_private");
            mutate("/rest/workspaces/ingest_private/coveragestores","POST",utf8(document.toString()),"application/json",deadline,beforeWrite);
            owned=checkStore(a,json(store(a)+".json",deadline,false).path("coverageStore"));
            require(owned,503,"PUBLISH_FAILED");
        }
        if(existingLayer==null){
            require(owned && json(store(a)+"/coverages/"+layer(a)+".json",deadline,true)==null,503,"PUBLISH_FAILED");
            mutate(store(a)+"/external.geotiff?configure=all&coverageName="+layer(a),"PUT",utf8(sourceUrl(a)),"text/plain",deadline,beforeWrite);
            owned=checkStore(a,json(store(a)+".json",deadline,false).path("coverageStore"));
            require(owned,503,"PUBLISH_FAILED");
        }
        JsonNode actualLayer=layerResource(a,deadline);
        if(!fixedStyle(actualLayer)){
            JsonNode defaultStyle=actualLayer.path("defaultStyle");
            require(owned && "raster".equals(defaultStyle.path("name").asText())
                    && defaultStyle.path("workspace").asText("").isEmpty(),503,"PUBLISH_FAILED");
            String binding="{\"layer\":{\"defaultStyle\":{\"name\":\"s2-rgb-v1\",\"workspace\":\"ingest_private\"}}}";
            mutate(layerPath,"PUT",utf8(binding),"application/json",deadline,beforeWrite);
        }
        source(a,deadline);
    }
    private byte[] utf8(String text){return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    @jakarta.annotation.PreDestroy public void close(){http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
}
