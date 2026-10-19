package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import static com.remotesensing.platform.agent.AgentJson.*;

/** Java independently validates internal output before any tool or persisted explanation. */
public final class AgentProtocol {
    public static final Set<String> METRICS = Set.of("statistics.mean", "statistics.min", "statistics.max",
            "statistics.totalPixelCount", "statistics.validPixelCount", "statistics.invalidPixelCount",
            "statistics.validPixelRatio", "coverageRatio");
    private AgentProtocol() {}

    public static JsonNode action(JsonNode output) {
        try {
            fields(output, "requestId", "action", "usage", "modelRequested", "modelReturned", "elapsedMs");
            JsonNode action = output.path("action");
            String kind = text(action, "kind", 30);
            switch (kind) {
                case "ASK_USER" -> {
                    fields(action, "kind", "question", "missingFields");
                    sensitive(text(action, "question", 500));
                    JsonNode missing = action.path("missingFields");
                    require(missing.isArray() && missing.size() <= 3);
                    missing.forEach(value -> require(value.isTextual() && Set.of("imageId", "monitoringRegionId", "goalClarification").contains(value.asText())));
                }
                case "UNSUPPORTED" -> { fields(action, "kind", "message"); sensitive(text(action, "message", 500)); }
                case "PROPOSE_NDVI" -> {
                    fields(action, "kind", "imageId", "monitoringRegionId", "params");
                    id(action, "imageId"); id(action, "monitoringRegionId");
                    fields(action.path("params"), "redBand", "nirBand");
                    require(integer(action.path("params"), "redBand", 1) == 3 && integer(action.path("params"), "nirBand", 1) == 4);
                }
                case "CALL_TOOL" -> {
                    fields(action, "kind", "tool", "arguments");
                    String tool = text(action, "tool", 30);
                    JsonNode args = action.path("arguments");
                    if (tool.equals("searchImages")) {
                        fields(args, "keyword", "startTime", "endTime", "sensor", "maxCloudPercent");
                        sensitive(optionalText(args, "keyword", 100)); sensitive(optionalText(args, "sensor", 50));
                        String start = optionalText(args, "startTime", 50), end = optionalText(args, "endTime", 50);
                        OffsetDateTime first = start == null ? null : OffsetDateTime.parse(start);
                        OffsetDateTime last = end == null ? null : OffsetDateTime.parse(end);
                        require(first == null || last == null || !first.isAfter(last));
                        JsonNode cloud = args.path("maxCloudPercent");
                        require(cloud.isMissingNode() || cloud.isNull() || (cloud.isNumber() && Double.isFinite(cloud.doubleValue())
                                && cloud.doubleValue() >= 0 && cloud.doubleValue() <= 100));
                    } else {
                        require(tool.equals("listRegions")); fields(args, "keyword"); sensitive(optionalText(args, "keyword", 100));
                    }
                }
                default -> throw input();
            }
            return action;
        } catch (AgentFailure | java.time.format.DateTimeParseException e) { throw invalid(); }
    }

    public static ObjectNode generated(JsonNode output, boolean explanation) {
        try {
            if (explanation) fields(output, "requestId", "knowledgeIndexId", "summary", "claims", "limitations", "insufficientEvidence",
                    "citations", "distances", "usage", "modelRequested", "modelReturned", "elapsedMs");
            else fields(output, "requestId", "knowledgeIndexId", "answer", "claims", "insufficientEvidence", "citations", "distances",
                    "usage", "modelRequested", "modelReturned", "elapsedMs");
            String index = hashField(output, "knowledgeIndexId");
            JsonNode citations = output.path("citations");
            require(citations.isArray() && citations.size() <= 5);
            Map<String, JsonNode> refs = new HashMap<>();
            int total = 0;
            for (JsonNode c : citations) {
                fields(c, "citationId", "sourceId", "title", "sourceKind", "heading", "headingAnchor", "chunkId", "documentSha256",
                        "chunkSha256", "knowledgeIndexId", "excerpt", "sourceUrl");
                String cid = text(c, "citationId", 2);
                require(cid.matches("C[1-5]") && refs.put(cid, c) == null);
                text(c, "sourceId", 100); text(c, "title", 255); text(c, "heading", 255); text(c, "headingAnchor", 255);
                require(Set.of("PLATFORM", "EXTERNAL_SCIENTIFIC").contains(text(c, "sourceKind", 30)));
                hashField(c, "chunkId"); hashField(c, "documentSha256");
                require(index.equals(hashField(c, "knowledgeIndexId")));
                String excerpt = text(c, "excerpt", 650);
                require(sha(excerpt).equals(hashField(c, "chunkSha256")));
                total += excerpt.codePointCount(0, excerpt.length());
                String url = optionalText(c, "sourceUrl", 2000);
                if (url != null) {
                    URI uri = URI.create(url);
                    require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null);
                }
            }
            require(total <= 3250);
            require(output.path("insufficientEvidence").isBoolean());
            boolean insufficient = output.path("insufficientEvidence").booleanValue();
            JsonNode claims = output.path("claims");
            require(claims.isArray() && claims.size() <= 8);
            String narrative = text(output, explanation ? "summary" : "answer", 1000);
            sensitive(narrative);
            if (explanation) {
                require(output.path("limitations").isArray() && output.path("limitations").isEmpty());
                require(narrative.codePoints().noneMatch(Character::isDigit));
            }
            if (insufficient) require(claims.isEmpty() && narrative.equals("知识库中没有足够信息"));
            else {
                require(!claims.isEmpty());
                List<String> texts = new ArrayList<>();
                for (JsonNode claim : claims) {
                    fields(claim, "text", "citationIds", "evidenceQuotes", "metricRefs");
                    String claimText = text(claim, "text", 500); sensitive(claimText); texts.add(claimText);
                    JsonNode ids = claim.path("citationIds"), quotes = claim.path("evidenceQuotes");
                    require(ids.isArray() && ids.size() >= 1 && ids.size() <= 5 && quotes.isArray() && quotes.size() >= 1 && quotes.size() <= 5);
                    Set<String> requested = new HashSet<>(), quoted = new HashSet<>();
                    for (JsonNode cid : ids) { require(cid.isTextual() && refs.containsKey(cid.asText())); requested.add(cid.asText()); }
                    for (JsonNode quote : quotes) {
                        fields(quote, "citationId", "quote");
                        String cid = text(quote, "citationId", 2), actual = text(quote, "quote", 650);
                        require(refs.containsKey(cid) && refs.get(cid).path("excerpt").asText().contains(actual)); quoted.add(cid);
                    }
                    require(requested.equals(quoted));
                    JsonNode metrics = claim.path("metricRefs");
                    if (!metrics.isMissingNode()) {
                        require(metrics.isArray() && metrics.size() <= 8 && (explanation || metrics.isEmpty()));
                        metrics.forEach(metric -> require(metric.isTextual() && METRICS.contains(metric.asText())));
                    }
                }
                require(narrative.equals(String.join("；", texts)));
            }
            ObjectNode safe = object().put("knowledgeIndexId", index).put("insufficientEvidence", insufficient)
                    .put(explanation ? "summary" : "answer", narrative);
            safe.set("claims", claims.deepCopy()); safe.set("citations", citations.deepCopy());
            if (explanation) safe.set("limitations", array());
            return safe;
        } catch (AgentFailure | IllegalArgumentException e) { throw invalid(); }
    }

    public static AgentFailure invalid() { return new AgentFailure("MODEL_OUTPUT_INVALID", "模型输出未通过协议或引用校验", 503, true); }
    public static ObjectNode trace(JsonNode output) {
        ObjectNode trace = object();
        for (String key : List.of("modelRequested", "modelReturned")) {
            String value = output.path(key).asText("");
            boolean safe = value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
            try { sensitive(value); } catch (AgentFailure e) { safe = false; }
            if (safe) trace.put(key, value); else trace.putNull(key);
        }
        ObjectNode usage = object();
        for (String key : List.of("input_tokens", "output_tokens", "total_tokens")) copyCounter(output.path("usage"), usage, key);
        for (String key : List.of("input_token_details", "output_token_details")) {
            ObjectNode details = object();
            for (String name : key.equals("input_token_details") ? List.of("audio", "text", "cache_read", "cache_creation") : List.of("audio", "text", "reasoning"))
                copyCounter(output.path("usage").path(key), details, name);
            if (!details.isEmpty()) usage.set(key, details);
        }
        if (usage.isEmpty()) trace.putNull("usage"); else trace.set("usage", usage);
        copyCounter(output, trace, "elapsedMs");
        return trace;
    }
    private static void copyCounter(JsonNode source, ObjectNode target, String key) {
        JsonNode number = source.path(key);
        if (number.isIntegralNumber() && number.canConvertToLong() && number.longValue() >= 0) target.put(key, number.longValue());
    }
}
