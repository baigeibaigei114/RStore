package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Strict small JSON requests and one shared canonical representation for stored hashes. */
public final class IngestionJson {
    public static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private IngestionJson() {}
    public static JsonNode read(HttpServletRequest request, String... fields) {
        try {
            IngestionProblem.require(request.getContentType()!=null && request.getContentType().split(";",2)[0].equalsIgnoreCase("application/json"),400,"INVALID_REQUEST");
            byte[] raw = request.getInputStream().readNBytes(65537);
            IngestionProblem.require(raw.length <= 65536,413,"UPLOAD_TOO_LARGE");
            JsonNode value = JSON.readTree(raw);
            fields(value, fields);
            return value;
        } catch (java.io.IOException error) { throw new IngestionProblem(400,"INVALID_REQUEST"); }
    }
    public static void fields(JsonNode value, String... fields) {
        IngestionProblem.require(value!=null && value.isObject() && value.size()==fields.length,400,"INVALID_REQUEST");
        Set<String> allowed = Set.of(fields);
        value.fieldNames().forEachRemaining(key -> IngestionProblem.require(allowed.contains(key),400,"INVALID_REQUEST"));
    }
    public static UUID uuid(JsonNode value, String key) {
        try {
            IngestionProblem.require(value.path(key).isTextual(),400,"INVALID_REQUEST");
            UUID id = UUID.fromString(value.path(key).textValue());
            IngestionProblem.require(id.toString().equals(value.path(key).textValue()),400,"INVALID_REQUEST");
            return id;
        } catch (IllegalArgumentException error) { throw new IngestionProblem(400,"INVALID_REQUEST"); }
    }
    public static String name(JsonNode value, String field, int maximum) {
        IngestionProblem.require(value.path(field).isTextual(),400,"INVALID_REQUEST");
        String text = value.path(field).textValue().trim();
        IngestionProblem.require(!text.isEmpty() && text.length()<=maximum && text.codePoints().noneMatch(Character::isISOControl),400,"INVALID_REQUEST");
        return text;
    }
    public static long integer(JsonNode value, String field, long minimum, long maximum) {
        JsonNode n = value.path(field);
        IngestionProblem.require(n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>=minimum && n.longValue()<=maximum,400,"INVALID_REQUEST");
        return n.longValue();
    }
    public static String canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add);
            keys.sort((a,b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()));
            return "{" + String.join(",", keys.stream().map(k -> quote(k)+":"+canonical(value.get(k))).toList()) + "}";
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>(); value.forEach(v -> items.add(canonical(v)));
            return "["+String.join(",",items)+"]";
        }
        if (value.isNumber()) {
            IngestionProblem.require(Double.isFinite(value.doubleValue()),400,"INVALID_REQUEST");
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.isTextual() ? quote(value.textValue()) : value.toString();
    }
    private static String quote(String value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static String sha(JsonNode value) { return sha(canonical(value).getBytes(StandardCharsets.UTF_8)); }
}
