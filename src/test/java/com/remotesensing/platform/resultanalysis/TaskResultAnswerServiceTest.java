package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.object;
import static com.remotesensing.platform.agent.AgentJson.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentFailure;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
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
    private final TaskResultSpatialEvidence spatial = mock(TaskResultSpatialEvidence.class);
    private final TaskResultAnswerService service =
            new TaskResultAnswerService(users, tasks, results, store, spatial);
    private final RsResultFile file = new RsResultFile();

    @BeforeEach
    void validRegionalResult() {
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
    void readsSavedTurnsInChronologicalOrderWithPaginationAndVersionHistory() {
        String version = service.get(TASK_ID, null, null).path("currentVersion").asText();
        var newestFirst = new java.util.ArrayList<TaskResultAnswerStore.Turn>();
        for (int n = 31; n >= 1; n--) newestFirst.add(new TaskResultAnswerStore.Turn(
                UUID.randomUUID(), version, n, "已保存的问题", "COMPLETE", "{\"kind\":\"VALID_RATIO\"}", null));
        when(store.history(OWNER, TASK_ID, version, Integer.MAX_VALUE, 31)).thenReturn(newestFirst);
        when(store.versions(OWNER, TASK_ID)).thenReturn(List.of(version,"b".repeat(64)));
        JsonNode response = service.get(TASK_ID, null, null);
        assertFalse(response.path("canAsk").asBoolean());
        assertTrue(response.path("hasEarlier").asBoolean());
        assertEquals(2, response.path("turns").get(0).path("number").asInt());
        assertEquals(31, response.path("turns").get(29).path("number").asInt());
        assertEquals("b".repeat(64),response.path("historyVersions").get(0).asText());
        assertFalse(response.toString().contains(file.getObjectKey()));
        service.get(TASK_ID, version, 2);
        verify(store).history(OWNER, TASK_ID, version, 2, 31);
    }

    @Test
    void oldVersionIsReadableButUnknownVersionIsNot() {
        String old = "b".repeat(64);
        when(store.versions(OWNER, TASK_ID)).thenReturn(List.of(old));
        when(store.history(OWNER,TASK_ID,old,Integer.MAX_VALUE,31)).thenReturn(List.of(
                new TaskResultAnswerStore.Turn(UUID.randomUUID(),old,1,"历史问题","COMPLETE","{}",null)));
        assertEquals(old,service.get(TASK_ID,old,null).path("resultVersion").asText());
        assertEquals("HISTORY_NOT_FOUND",assertThrows(AgentFailure.class,
                () -> service.get(TASK_ID,"c".repeat(64),null)).errorCode);
    }

    @Test
    void historyDoesNotDependOnCurrentMetadataOrModelAvailability() {
        file.setResultMetadata("{}");
        when(store.versions(OWNER,TASK_ID)).thenReturn(List.of("b".repeat(64)));
        JsonNode response=service.get(TASK_ID,"b".repeat(64),null);
        assertTrue(response.path("currentVersion").isNull());
        assertFalse(response.path("canAsk").asBoolean());
        assertTrue(response.has("message"));
    }

    @Test
    void anotherOwnerCannotReadHistoryOrEvidence() {
        when(users.getAuthenticatedUserId()).thenReturn("another");
        assertEquals("TASK_NOT_FOUND",assertThrows(AgentFailure.class,
                () -> service.get(TASK_ID,null,null)).errorCode);
        assertEquals("TASK_NOT_FOUND",assertThrows(AgentFailure.class,
                () -> service.evidence(TASK_ID,"a".repeat(64),"NW")).errorCode);
        verifyNoInteractions(store, spatial);
    }

    @Test
    void evidenceUsesExactVersionAndNamedSectionAndRejectsResultReplacement() {
        String version=service.get(TASK_ID,null,null).path("currentVersion").asText();
        when(spatial.geometry(OWNER,TASK_ID,version,"NW",file)).thenReturn(object().put("type","Polygon"));
        JsonNode evidence=service.evidence(TASK_ID,version,"NW");
        assertEquals("9",evidence.path("fileId").asText());
        assertEquals("NW",evidence.path("section").asText());
        assertEquals("Polygon",evidence.path("geometry").path("type").asText());
        doAnswer(call -> { file.setChecksum("replacement"); return object(); })
                .when(spatial).geometry(OWNER,TASK_ID,version,"NW",file);
        assertEquals("RESULT_CHANGED",assertThrows(AgentFailure.class,
                () -> service.evidence(TASK_ID,version,"NW")).errorCode);
        assertThrows(AgentFailure.class,() -> service.evidence(TASK_ID,version,"CUSTOM"));
    }
}
