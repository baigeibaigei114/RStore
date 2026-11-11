package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.object;
import static com.remotesensing.platform.agent.AgentJson.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentClient;
import com.remotesensing.platform.agent.AgentFailure;
import com.remotesensing.platform.agent.AgentProperties;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RateLimitService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskResultAnswerServiceTest {
    private static final long TASK_ID = 7L;
    private static final String OWNER = "owner";
    private static final String REGION = """
            {"schemaVersion":1,"version":3,"regionId":5,"name":"区域","crs":"EPSG:4326",
             "pixelRule":"center_covered","geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}}
            """;

    private final CurrentUserContext users = mock(CurrentUserContext.class);
    private final RsTaskMapper tasks = mock(RsTaskMapper.class);
    private final RsResultFileMapper results = mock(RsResultFileMapper.class);
    private final TaskResultAnswerStore store = mock(TaskResultAnswerStore.class);
    private final AgentClient agent = mock(AgentClient.class);
    private final RateLimitService rate = mock(RateLimitService.class);
    private final TaskResultSpatialEvidence spatial = mock(TaskResultSpatialEvidence.class);
    private final AgentProperties limits = new AgentProperties();
    private final TaskResultAnswerService service =
            new TaskResultAnswerService(users, tasks, results, store, agent, limits, rate, spatial);
    private final RsResultFile file = new RsResultFile();

    @BeforeEach
    void validRegionalResult() {
        limits.setEnabled(true);
        when(users.getAuthenticatedUserId()).thenReturn(OWNER);
        RsTask task = new RsTask();
        task.setId(TASK_ID);
        task.setOwnerId(OWNER);
        task.setStatus("SUCCESS");
        task.setTaskType("NDVI");
        task.setImageId(8L);
        task.setParams("{\"regionSnapshot\":" + REGION + "}");
        when(tasks.selectByIdForOwner(TASK_ID, OWNER)).thenReturn(task);
        file.setId(9L);
        file.setTaskId(TASK_ID);
        file.setImageId(8L);
        file.setOwnerId(OWNER);
        file.setObjectKey("internal/never-sent.tif");
        file.setResultMetadata("""
                {"schemaVersion":2,"scope":"monitoring_region","algorithm":"NDVI","coverageRatio":1,
                 "bandMapping":{"redBand":3,"nirBand":4},"regionSnapshot":%s,
                 "statistics":{"totalPixelCount":8,"validPixelCount":6,"invalidPixelCount":2,
                               "validPixelRatio":0.75,"min":-0.1,"max":0.8,"mean":0.4}}
                """.formatted(REGION));
        when(results.selectByTaskId(TASK_ID)).thenReturn(file);
    }

    @Test
    void rendersStoredEvidenceAndReplaysWithoutSecondModelCall() {
        String question = "有效像素比例是多少？";
        ObjectNode request = question(question);
        UUID requestId = UUID.fromString(request.path("requestId").asText());
        String version = request.path("resultVersion").asText();
        when(store.reserve(OWNER, TASK_ID, requestId, version, question, 90, 0)).thenReturn(true);
        when(agent.post("result-answers", object().put("question", question), Duration.ofSeconds(30)))
                .thenReturn(object().put("kind", "VALID_PIXELS"));
        doAnswer(invocation -> {
            String answer = invocation.getArgument(3);
            when(store.find(OWNER, TASK_ID, requestId)).thenReturn(
                    new TaskResultAnswerStore.Turn(requestId, version, 1, question, "COMPLETE", answer, null));
            return null;
        }).when(store).complete(eq(OWNER), eq(TASK_ID), eq(requestId), anyString());

        JsonNode first = service.ask(TASK_ID, request);
        assertEquals("COMPLETE", first.path("status").asText());
        JsonNode evidence = first.path("answer").path("evidence");
        assertEquals(6, evidence.get(0).path("value").asInt());
        assertEquals(8, evidence.get(1).path("value").asInt());
        assertEquals(0.75, evidence.get(3).path("value").asDouble());
        assertEquals(first, service.ask(TASK_ID, request));
        verify(agent, times(1)).post("result-answers", object().put("question", question), Duration.ofSeconds(30));
        assertFalse(first.toString().contains(file.getObjectKey()));
    }

    @Test
    void deniesUnownedOrStaleResultBeforeReservingOrCallingModel() {
        ObjectNode request = question("均值是多少？");
        file.setObjectKey("internal/different-version.tif");
        assertEquals("RESULT_CHANGED", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, request)).errorCode);

        when(users.getAuthenticatedUserId()).thenReturn("another");
        assertEquals("TASK_NOT_FOUND", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, request)).errorCode);
        verify(store, never()).reserve(anyString(), anyLong(), any(), anyString(), anyString(), anyInt(), anyInt());
        verifyNoInteractions(agent);
    }

    @Test
    void missingStatisticsStopBeforeModel() {
        ObjectNode request = question("均值是多少？");
        file.setResultMetadata(null);
        assertEquals("STATISTICS_UNAVAILABLE", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, request)).errorCode);
        verifyNoInteractions(agent);
    }

    @Test
    void discardsAnAnswerWhenTheResultChangesDuringGeneration() {
        String question = "均值是多少？";
        ObjectNode request = question(question);
        UUID requestId = UUID.fromString(request.path("requestId").asText());
        when(store.reserve(eq(OWNER), eq(TASK_ID), eq(requestId), anyString(), eq(question), anyInt(), eq(0))).thenReturn(true);
        when(agent.post(eq("result-answers"), any(), any())).thenAnswer(ignored -> {
            file.setObjectKey("internal/new-attempt.tif");
            return object().put("kind", "MEAN");
        });

        assertEquals("RESULT_CHANGED", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, request)).errorCode);
        verify(store).fail(eq(OWNER), eq(TASK_ID), eq(requestId), anyString());
        verify(store, never()).complete(anyString(), anyLong(), any(), anyString());
    }

    @Test
    void restoresOrderedHistoryAndOnlySendsThePreviousVerifiedTopicOnFollowUp() {
        String followUp = "它的分母是多少？";
        ObjectNode request = question(followUp).put("expectedTurn", 1);
        String version = request.path("resultVersion").asText();
        UUID requestId = UUID.fromString(request.path("requestId").asText());
        var first = new TaskResultAnswerStore.Turn(UUID.randomUUID(), version, 1,
                "比例是多少？", "COMPLETE", "{\"kind\":\"VALID_RATIO\"}", null);
        when(store.history(OWNER, TASK_ID, version, Integer.MAX_VALUE, 31)).thenReturn(List.of(first));
        when(store.previousComplete(OWNER, TASK_ID, version, 1)).thenReturn(first);
        when(store.reserve(OWNER, TASK_ID, requestId, version, followUp, 90, 1)).thenReturn(true);
        ObjectNode prompt = object().put("question", followUp).put("previousKind", "VALID_RATIO");
        when(agent.post("result-answers", prompt, Duration.ofSeconds(30))).thenReturn(object().put("kind", "FOLLOW_UP"));
        doAnswer(invocation -> {
            var second = new TaskResultAnswerStore.Turn(requestId, version, 2,
                    followUp, "COMPLETE", invocation.getArgument(3), null);
            when(store.find(OWNER, TASK_ID, requestId)).thenReturn(second);
            when(store.history(OWNER, TASK_ID, version, Integer.MAX_VALUE, 31)).thenReturn(List.of(second, first));
            return null;
        }).when(store).complete(eq(OWNER), eq(TASK_ID), eq(requestId), anyString());

        assertEquals("比例是多少？", service.get(TASK_ID, null, null).path("turns").get(0).path("question").asText());
        verifyNoInteractions(agent);
        assertEquals(8, service.ask(TASK_ID, request).path("answer").path("evidence").get(1).path("value").asInt());
        var restored = service.get(TASK_ID, null, null).path("turns");
        assertEquals(1, restored.get(0).path("number").asInt());
        assertEquals(2, restored.get(1).path("number").asInt());
        verify(agent, times(1)).post("result-answers", prompt, Duration.ofSeconds(30));
    }

    @Test
    void priorVersionRemainsReadableAndStaleTurnCannotCallModel() {
        ObjectNode stale = question("它的分母是多少？").put("expectedTurn", 1);
        String version = stale.path("resultVersion").asText();
        var earlier = new TaskResultAnswerStore.Turn(UUID.randomUUID(), version, 1,
                "比例是多少？", "COMPLETE", "{\"kind\":\"VALID_PIXELS\"}", null);
        assertEquals("TURN_CHANGED", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, stale)).errorCode);
        file.setObjectKey("internal/new-attempt.tif");
        when(store.versions(OWNER, TASK_ID)).thenReturn(List.of(version));
        when(store.history(OWNER, TASK_ID, version, Integer.MAX_VALUE, 31)).thenReturn(List.of(earlier));

        JsonNode old = service.get(TASK_ID, version, null);
        assertFalse(old.path("canAsk").asBoolean());
        assertEquals(1, old.path("turns").size());
        assertNotEquals(version, old.path("currentVersion").asText());
        assertEquals(0, service.get(TASK_ID, null, null).path("turns").size());
        limits.setEnabled(false);
        JsonNode disabled = service.get(TASK_ID, version, null);
        assertFalse(disabled.path("canAsk").asBoolean());
        assertEquals(old.path("turns"), disabled.path("turns"));
        verifyNoInteractions(agent);
    }

    @Test
    void requestsClarificationForAnAmbiguousFollowUp() {
        String question = "它是多少？";
        ObjectNode request = question(question).put("expectedTurn", 1);
        String version = request.path("resultVersion").asText();
        UUID requestId = UUID.fromString(request.path("requestId").asText());
        var range = new TaskResultAnswerStore.Turn(UUID.randomUUID(), version, 1,
                "最小值和最大值是多少？", "COMPLETE", "{\"kind\":\"RANGE\"}", null);
        when(store.previousComplete(OWNER, TASK_ID, version, 1)).thenReturn(range);
        when(store.reserve(OWNER, TASK_ID, requestId, version, question, 90, 1)).thenReturn(true);
        when(agent.post(eq("result-answers"), any(), any())).thenReturn(object().put("kind", "FOLLOW_UP"));
        doAnswer(invocation -> {
            when(store.find(OWNER, TASK_ID, requestId)).thenReturn(new TaskResultAnswerStore.Turn(
                    requestId, version, 2, question, "COMPLETE", invocation.getArgument(3), null));
            return null;
        }).when(store).complete(eq(OWNER), eq(TASK_ID), eq(requestId), anyString());

        JsonNode answer = service.ask(TASK_ID, request).path("answer");
        assertEquals("CLARIFY", answer.path("kind").asText());
        assertTrue(answer.path("evidence").isEmpty());
    }

    @Test
    void restoresTheHighestPartitionAndUsesItsOwnPixelCountForFollowUp() {
        ObjectNode first = question("哪个分区均值最高？");
        String version = first.path("resultVersion").asText();
        JsonNode sections = parse("""
                {"sections":[{"id":"NW","total":2,"valid":2,"mean":0.0},
                {"id":"NE","total":2,"valid":2,"mean":-0.1},
                {"id":"SW","total":2,"valid":0,"mean":null},
                {"id":"SE","total":2,"valid":2,"mean":0.8,"geometry":{"type":"Polygon"}}]}
                """);
        when(spatial.extract(eq(OWNER), eq(TASK_ID), eq(version), eq(file), any())).thenReturn(sections);
        var initial = complete(first, object().put("question", first.path("question").asText()), "TOP_SECTION");
        assertEquals("SE", initial.path("answer").path("focusSection").asText());
        assertEquals(0.8, initial.path("answer").path("partitions").get(0).path("mean").asDouble());
        assertFalse(initial.toString().contains("geometry"));
        var prior = new TaskResultAnswerStore.Turn(UUID.fromString(initial.path("requestId").asText()), version, 1,
                first.path("question").asText(), "COMPLETE", initial.path("answer").toString(), null);
        when(store.previousComplete(OWNER, TASK_ID, version, 1)).thenReturn(prior);

        ObjectNode followUp = question("那一块有多少有效像素？").put("expectedTurn", 1);
        var answer = complete(followUp, object().put("question", followUp.path("question").asText())
                .put("previousSection", "SE"), "FOLLOW_UP_SECTION").path("answer");
        assertEquals("SE", answer.path("focusSection").asText());
        assertEquals(2, answer.path("partitions").get(0).path("valid").asInt());
    }

    @Test
    void asksForASectionWhenOnlyAnOverallMeanWasPreviouslyDiscussed() {
        ObjectNode followUp = question("那一块有多少有效像素？").put("expectedTurn", 1);
        String version = followUp.path("resultVersion").asText();
        when(store.previousComplete(OWNER, TASK_ID, version, 1)).thenReturn(new TaskResultAnswerStore.Turn(
                UUID.randomUUID(), version, 1, "均值是多少？", "COMPLETE", "{\"kind\":\"MEAN\"}", null));
        var answer = complete(followUp, object().put("question", followUp.path("question").asText())
                .put("previousKind", "MEAN"), "FOLLOW_UP_SECTION").path("answer");
        assertEquals("CLARIFY", answer.path("kind").asText());
        verifyNoInteractions(spatial);
    }

    @Test
    void blocksGeometryForAnOldVersionOrAnotherAccountBeforeAccessingStorage() {
        String version = service.get(TASK_ID, null, null).path("resultVersion").asText();
        file.setObjectKey("internal/other-result.tif");
        assertEquals("RESULT_CHANGED", assertThrows(AgentFailure.class,
                () -> service.evidence(TASK_ID, version, "NW")).errorCode);
        when(users.getAuthenticatedUserId()).thenReturn("other");
        assertEquals("TASK_NOT_FOUND", assertThrows(AgentFailure.class,
                () -> service.evidence(TASK_ID, version, "NW")).errorCode);
        verifyNoInteractions(spatial, agent);
    }

    @Test
    void readsNdwiWithoutARegionAndRefusesWaterAreaWithoutClassification() {
        RsTask task = tasks.selectByIdForOwner(TASK_ID, OWNER);
        task.setTaskType("NDWI");
        task.setParams("{\"greenBand\":2,\"nirBand\":4}");
        file.setResultMetadata("""
                {"schemaVersion":1,"scope":"cropped_raster","algorithm":"NDWI","bandMapping":{"greenBand":2,"nirBand":4},
                 "statistics":{"totalPixelCount":8,"validPixelCount":6,"invalidPixelCount":2,
                               "validPixelRatio":0.75,"min":-0.5,"max":0.5,"mean":0}}
                """);
        ObjectNode request = question("水体面积是多少？");
        var answer = complete(request, object().put("question", "水体面积是多少？").put("algorithm", "NDWI"),
                "WATER_AREA_UNAVAILABLE").path("answer");
        assertTrue(answer.path("regionVersion").isNull());
        assertTrue(answer.path("statement").asText().contains("需要水体分类"));
        assertTrue(answer.path("evidence").isEmpty());
        verifyNoInteractions(spatial);

        file.setResultMetadata(null);
        assertFalse(service.get(TASK_ID, null, null).path("canAsk").asBoolean());
        assertEquals("STATISTICS_UNAVAILABLE", assertThrows(AgentFailure.class,
                () -> service.ask(TASK_ID, request)).errorCode);
    }

    private ObjectNode complete(ObjectNode request, ObjectNode prompt, String kind) {
        String question = request.path("question").asText();
        String version = request.path("resultVersion").asText();
        int expectedTurn = request.path("expectedTurn").asInt();
        UUID id = UUID.fromString(request.path("requestId").asText());
        when(store.reserve(OWNER, TASK_ID, id, version, question, 90, expectedTurn)).thenReturn(true);
        when(agent.post("result-answers", prompt, Duration.ofSeconds(30))).thenReturn(object().put("kind", kind));
        doAnswer(invocation -> {
            when(store.find(OWNER, TASK_ID, id)).thenReturn(new TaskResultAnswerStore.Turn(id, version,
                    expectedTurn + 1, question, "COMPLETE", invocation.getArgument(3), null));
            return null;
        }).when(store).complete(eq(OWNER), eq(TASK_ID), eq(id), anyString());
        return service.ask(TASK_ID, request);
    }

    private ObjectNode question(String text) {
        return object().put("requestId", UUID.randomUUID().toString()).put("expectedTurn", 0)
                .put("resultVersion", service.get(TASK_ID, null, null).path("resultVersion").asText()).put("question", text);
    }
}
