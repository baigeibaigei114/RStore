package com.remotesensing.platform.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Exact wire validation and deterministic hashing, independent of global Jackson settings. */
public final class AgentJson {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern SENSITIVE = Pattern.compile(
            "sk-[\\w-]{12,}|(?i:bearer\\s+\\S+|(?:api[_-]?key|password|token|secret)\\s*[:=]\\s*\\S+|https?://|postgres(?:ql)?://|s3://|\\b(?:bucket|objectKey|ownerId|userId)\\b)|[A-Za-z]:[\\\\/]" );
    private AgentJson() {}
    public static ObjectNode object() { return JSON.createObjectNode(); }
    public static ArrayNode array() { return JSON.createArrayNode(); }
    public static JsonNode parse(String value) {
        try { return value == null ? NullNode.instance : JSON.readTree(value); }
        catch (java.io.IOException e) { throw input(); }
    }
    public static void fields(JsonNode node, String... allowed) {
        require(node != null && node.isObject());
        Set<String> names = Set.of(allowed);
        node.fieldNames().forEachRemaining(key -> require(names.contains(key)));
    }
    public static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank()
                && value.textValue().codePointCount(0, value.textValue().length()) <= limit);
        return value.textValue();
    }
    public static String optionalText(JsonNode node, String field, int limit) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : text(node, field, limit);
    }
    public static String uuid(JsonNode node, String field) {
        String value = text(node, field, 36);
        require(value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        return UUID.fromString(value).toString();
    }
    public static long id(JsonNode node, String field) {
        String value = text(node, field, 19);
        require(value.matches("[1-9][0-9]{0,18}"));
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw input(); }
    }
    public static int integer(JsonNode node, String field, int min) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= min);
        return value.intValue();
    }
    public static String hashField(JsonNode node, String field) {
        String value = text(node, field, 64);
        require(value.matches("[a-f0-9]{64}"));
        return value;
    }
    public static void sensitive(String value) {
        if (value != null && SENSITIVE.matcher(value).find())
            throw new AgentFailure("SENSITIVE_INPUT", "输入包含敏感信息或资源路径，请移除后重试", 400, false);
    }
    public static void require(boolean valid) { if (!valid) throw input(); }
    public static AgentFailure input() { return new AgentFailure("INPUT_INVALID", "请求字段不合法", 400, false); }
    public static String canonical(JsonNode value) {
        if (value.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            List<String> fields = new ArrayList<>();
            sorted.forEach((key, child) -> fields.add(JSON.valueToTree(key).toString() + ":" + canonical(child)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value.isArray()) {
            List<String> children = new ArrayList<>();
            value.forEach(child -> children.add(canonical(child)));
            return "[" + String.join(",", children) + "]";
        }
        if (value.isNumber()) {
            require(Double.isFinite(value.doubleValue()));
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }
    public static String hash(JsonNode value) { return sha(canonical(value)); }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
