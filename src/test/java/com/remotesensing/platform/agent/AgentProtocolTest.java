package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.remotesensing.platform.agent.AgentJson.*;

class AgentProtocolTest {
    static ObjectNode valid() {
        String text = "有效像素比例不是植被覆盖率";
        String excerpt = text + "。";
        String digest = "a".repeat(64);
        ObjectNode citation = object().put("citationId", "C1").put("sourceId", "platform-ndvi").put("title", "方法")
                .put("sourceKind", "PLATFORM").put("heading", "限制").put("headingAnchor", "limits").put("chunkId", digest)
                .put("documentSha256", digest).put("chunkSha256", sha(excerpt)).put("knowledgeIndexId", digest).put("excerpt", excerpt).putNull("sourceUrl");
        ObjectNode output = object().put("knowledgeIndexId", digest).put("answer", text).put("insufficientEvidence", false);
        output.set("citations", array().add(citation));
        output.set("claims", array().add(object().put("text", text).set("citationIds", array().add("C1"))));
        ((ObjectNode)output.path("claims").get(0)).set("evidenceQuotes", array().add(object().put("citationId", "C1").put("quote", text)));
        return output;
    }
    @Test void canonicalHashSortsKeysNumbersAndNullButPreservesArrayOrder() {
        assertThat(canonical(parse("{\"z\":null,\"a\":1.00}"))).isEqualTo("{\"a\":1,\"z\":null}");
        assertThat(hash(parse("{\"a\":1,\"z\":null}"))).isEqualTo(hash(parse("{\"z\":null,\"a\":1.0}")));
        assertThat(hash(parse("[1,2]"))).isNotEqualTo(hash(parse("[2,1]")));
    }
    @Test void duplicateJsonFailsClosed() {
        assertThatThrownBy(() -> parse("{\"a\":1,\"a\":2}")).isInstanceOf(AgentFailure.class);
    }
    @Test void knowledgeAnswerRequiresBoundClaimsQuotesAndNoTaskMetricReferences() {
        assertThat(AgentProtocol.generated(valid()).path("answer").asText()).contains("有效");
        for (String change : new String[]{"quote","unknown","digest","answer","metric","extra","insufficient"}) {
            ObjectNode output=valid(); ObjectNode claim=(ObjectNode)output.path("claims").get(0);
            switch(change) {
                case "quote" -> ((ObjectNode)claim.path("evidenceQuotes").get(0)).put("quote","password=synthetic-only");
                case "unknown" -> claim.set("citationIds",array().add("C2"));
                case "digest" -> ((ObjectNode)output.path("citations").get(0)).put("chunkSha256","b".repeat(64));
                case "answer" -> output.put("answer","额外结论");
                case "metric" -> claim.set("metricRefs",array().add("statistics.mean"));
                case "extra" -> output.put("tool","submitTask");
                default -> output.put("insufficientEvidence",true);
            }
            assertThatThrownBy(() -> AgentProtocol.generated(output)).isInstanceOf(AgentFailure.class).hasMessageNotContaining("synthetic-only");
        }
    }
    @Test void insufficientAnswerCannotIncludeClaims() {
        ObjectNode output=valid().put("answer","知识库中没有足够信息").put("insufficientEvidence",true);
        assertThatThrownBy(() -> AgentProtocol.generated(output)).isInstanceOf(AgentFailure.class);
        output.set("claims",array()); AgentProtocol.generated(output);
    }
    @Test void usageFilteringDoesNotInferZeroOrCopyHiddenContent() {
        ObjectNode response = object().put("modelReturned", "password=synthetic-only");
        response.set("usage", object().put("total_tokens",77).put("input_tokens", true).put("output_tokens",-1).put("private", "hidden"));
        assertThat(AgentProtocol.trace(response).path("usage").toString()).isEqualTo("{\"total_tokens\":77}");
        assertThat(AgentProtocol.trace(response).path("modelReturned").isNull()).isTrue();
        assertThat(AgentProtocol.trace(object()).path("usage").isNull()).isTrue();
    }
}
