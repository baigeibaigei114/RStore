package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.object;
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
    private final AgentProperties limits = new AgentProperties();
    private final TaskResultAnswerService service = new TaskResultAnswerService(users, tasks, results, store, agent, limits, rate);
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
        when(store.reserve(OWNER, TASK_ID, requestId, version, question, 90)).thenReturn(true);
        when(agent.post("result-answers", object().put("question", question), Duration.ofSeconds(30)))
                .thenReturn(object().put("kind", "VALID_PIXELS"));
        doAnswer(invocation -> {
            String answer = invocation.getArgument(3);
            when(store.find(OWNER, TASK_ID, requestId)).thenReturn(
                    new TaskResultAnswerStore.Turn(requestId, version, question, "COMPLETE", answer, null));
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
        verify(store, never()).reserve(anyString(), anyLong(), any(), anyString(), anyString(), anyInt());
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
        when(store.reserve(eq(OWNER), eq(TASK_ID), eq(requestId), anyString(), eq(question), anyInt())).thenReturn(true);
        when(agent.post(eq("result-answers"), any(), any())).thenAnswer(ignored -> {
            file.setObjectKey("internal/new-attempt.tif");
            return object().put("kind", "MEAN");
        });

        assertEquals("RESULT_CHANGED", assertThrows(AgentFailure.class, () -> service.ask(TASK_ID, request)).errorCode);
        verify(store).fail(eq(OWNER), eq(TASK_ID), eq(requestId), anyString());
        verify(store, never()).complete(anyString(), anyLong(), any(), anyString());
    }

    private ObjectNode question(String text) {
        return object().put("requestId", UUID.randomUUID().toString())
                .put("resultVersion", service.get(TASK_ID).path("resultVersion").asText()).put("question", text);
    }
}
