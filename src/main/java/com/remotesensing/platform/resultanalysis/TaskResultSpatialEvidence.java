package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentFailure;
import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.config.properties.PythonWorkerProperties;
import com.remotesensing.platform.entity.RsResultFile;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads a private result under a byte/time budget and stores evidence by immutable result identity. */
@Service
class TaskResultSpatialEvidence {
    private static final Logger LOG = LoggerFactory.getLogger(TaskResultSpatialEvidence.class);
    private static final long MAX_RASTER_BYTES = 64L * 1024 * 1024;
    private static final long MAX_JSON_BYTES = 256L * 1024;
    private static final int PROCESS_SECONDS = 12;
    static final Set<String> SECTIONS = Set.of("NW", "NE", "SW", "SE");

    private final MinioProperties minio;
    private final PythonWorkerProperties python;
    private final JdbcTemplate jdbc;
    private final Semaphore slots = new Semaphore(2);
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3)).callTimeout(Duration.ofSeconds(8))
            .build();

    TaskResultSpatialEvidence(MinioProperties minio, PythonWorkerProperties python, JdbcTemplate jdbc) {
        this.minio = minio;
        this.python = python;
        this.jdbc = jdbc;
    }

    JsonNode extract(String owner, long taskId, String version, RsResultFile file, JsonNode metadata) {
        if (!slots.tryAcquire()) throw unavailable();
        try {
            MinioClient client = client();
            var object = client.statObject(StatObjectArgs.builder().bucket(file.getMinioBucket()).object(file.getObjectKey()).build());
            require(object.size() > 0 && object.size() <= MAX_RASTER_BYTES);
            require(object.etag() != null && object.etag().matches("[0-9a-fA-F-]{1,128}"));
            Cached cached = cached(owner, taskId, version);
            if (cached != null) return verified(cached, object.etag());
            JsonNode evidence = compute(client, file, metadata, object.etag(), object.size());
            jdbc.update("""
                    INSERT INTO task_result_spatial_evidence (owner_id, task_id, result_version, object_etag, evidence)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                    """, owner, taskId, version, object.etag(), evidence.toString());
            return verified(cached(owner, taskId, version), object.etag());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception error) {
            LOG.debug("Result spatial evidence unavailable for task {} ({})", taskId, error.getClass().getSimpleName());
            throw unavailable();
        } finally {
            slots.release();
        }
    }

    JsonNode geometry(String owner, long taskId, String version, String section, RsResultFile file) {
        if (!SECTIONS.contains(section) || !"PUBLISHED".equals(file.getStatus()))
            throw new AgentFailure("MAP_EVIDENCE_UNAVAILABLE", "当前结果图层暂不可用", 409, false);
        try {
            Cached cached = cached(owner, taskId, version);
            if (cached == null) throw unavailable();
            var object = client().statObject(StatObjectArgs.builder().bucket(file.getMinioBucket()).object(file.getObjectKey()).build());
            JsonNode evidence = verified(cached, object.etag());
            for (JsonNode block : evidence.path("sections")) {
                if (section.equals(block.path("id").asText()) && block.path("geometry").isObject())
                    return block.path("geometry");
            }
        } catch (Exception error) {
            throw new AgentFailure("MAP_EVIDENCE_UNAVAILABLE", "找不到与当前任务结果匹配的分区范围", 409, false);
        }
        throw new AgentFailure("MAP_EVIDENCE_UNAVAILABLE", "该分区暂无法定位", 409, false);
    }

    private Cached cached(String owner, long taskId, String version) {
        return jdbc.query("""
                SELECT object_etag, evidence::text AS evidence FROM task_result_spatial_evidence
                WHERE owner_id=? AND task_id=? AND result_version=?
                """, (row, index) -> new Cached(row.getString("object_etag"), parse(row.getString("evidence"))),
                owner, taskId, version).stream().findFirst().orElse(null);
    }

    private JsonNode verified(Cached cached, String etag) {
        if (cached == null || !cached.etag().equals(etag)) throw unavailable();
        return cached.evidence();
    }

    private JsonNode compute(MinioClient client, RsResultFile file, JsonNode metadata, String etag, long size) throws Exception {
        Path folder = Files.createTempDirectory("result-analysis-");
        Path raster = folder.resolve("result.tif");
        Path input = folder.resolve("metadata.json");
        Path output = folder.resolve("evidence.json");
        Process process = null;
        try {
            download(client, file, etag, size, raster);
            Files.writeString(input, metadata.toString());
            Path helper = Path.of(python.getParseMetadataScript()).toAbsolutePath().resolveSibling("summarize_result_analysis.py");
            var builder = new ProcessBuilder(python.getPythonExecutable(), "-B", helper.toString(),
                    raster.toString(), input.toString(), output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(Map.of("GDAL_NUM_THREADS", "1", "OMP_NUM_THREADS", "1", "OPENBLAS_NUM_THREADS", "1"));
            process = builder.start();
            if (!process.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) throw unavailable();
            require(Files.isRegularFile(output) && Files.size(output) <= MAX_JSON_BYTES);
            return validate(parse(Files.readString(output)), metadata.path("statistics"));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw unavailable();
            }
            Files.deleteIfExists(output);
            Files.deleteIfExists(input);
            Files.deleteIfExists(raster);
            Files.deleteIfExists(folder);
        }
    }

    private void download(MinioClient client, RsResultFile file, String etag, long size, Path raster) throws Exception {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(file.getMinioBucket()).object(file.getObjectKey())
                .extraHeaders(Map.of("If-Match", "\"" + etag + "\"")).build());
             var output = Files.newOutputStream(raster)) {
            long count = 0;
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                require(count <= size);
                output.write(buffer, 0, read);
            }
            require(count == size);
        }
    }

    private ObjectNode validate(JsonNode raw, JsonNode saved) {
        double min = saved.path("min").asDouble(), max = saved.path("max").asDouble();
        double p10 = number(raw.path("p10"), min, max);
        double p50 = number(raw.path("p50"), p10, max);
        double p90 = number(raw.path("p90"), p50, max);
        ObjectNode evidence = object().put("p10", p10).put("p50", p50).put("p90", p90);
        var blocks = evidence.putArray("sections");
        Set<String> seen = new HashSet<>();
        long total = 0, valid = 0;
        int srid = raw.path("srid").asInt(0);
        for (JsonNode section : raw.path("sections")) {
            String id = section.path("id").asText();
            require(SECTIONS.contains(id) && seen.add(id));
            int count = integer(section, "total", 0), good = integer(section, "valid", 0);
            require(good <= count);
            total += count;
            valid += good;
            ObjectNode block = blocks.addObject().put("id", id).put("total", count).put("valid", good);
            if (good == 0) block.putNull("mean");
            else block.put("mean", number(section.path("mean"), min, max));
            JsonNode shape = section.path("polygon");
            if (srid > 0 && count > 0 && shape.isObject()) {
                String geojson = jdbc.queryForObject("""
                        SELECT ST_AsGeoJSON(ST_Transform(ST_CollectionExtract(ST_Intersection(
                            ST_SetSRID(ST_GeomFromGeoJSON(?), ?), ST_SetSRID(ST_GeomFromGeoJSON(?), ?)), 3), 4326), 12)
                        """, String.class, raw.path("projectedRegion").toString(), srid, shape.toString(), srid);
                block.set("geometry", parse(geojson));
            }
        }
        require(seen.equals(SECTIONS) && total == saved.path("totalPixelCount").longValue()
                && valid == saved.path("validPixelCount").longValue());
        return evidence;
    }

    private double number(JsonNode node, double low, double high) {
        double value = node.asDouble(Double.NaN);
        require(node.isNumber() && Double.isFinite(value) && value >= low && value <= high);
        return value;
    }

    private MinioClient client() {
        return MinioClient.builder().endpoint(minio.getEndpoint()).credentials(minio.getAccessKey(), minio.getSecretKey())
                .region(minio.getRegion()).httpClient(http).build();
    }

    private AgentFailure unavailable() {
        return new AgentFailure("SPATIAL_EVIDENCE_UNAVAILABLE", "暂时无法核对分区证据，仍可查看基础统计", 409, false);
    }

    @PreDestroy
    void close() {
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    private record Cached(String etag, JsonNode evidence) {}
}
