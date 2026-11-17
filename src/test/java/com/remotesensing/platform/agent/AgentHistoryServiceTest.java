package com.remotesensing.platform.agent;

import static com.remotesensing.platform.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class AgentHistoryServiceTest {
    private final AgentMapper store = mock(AgentMapper.class);
    private final AgentClient client = mock(AgentClient.class);
    private final RsTaskMapper tasks = mock(RsTaskMapper.class);
    private final CurrentUserContext users = mock(CurrentUserContext.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AgentService service = new AgentService(store, client, tasks, mock(RsResultFileMapper.class),
            users, new AgentProperties(), transactions);

    @Test void intermediateAndTerminalRecordsAreReadWithoutRecoveryWritesOrProviderCalls() {
        when(users.getAuthenticatedUserId()).thenReturn("owner");
        String id = UUID.randomUUID().toString();
        AgentExecution row = new AgentExecution();
        row.setId(id); row.setOwnerId("owner"); row.setVersion(3);
        row.setOperationId(UUID.randomUUID().toString()); row.setOperationDeadline(OffsetDateTime.now().minusDays(1));
        row.setDraftJson("{\"imageName\":\"历史影像\",\"regionName\":\"历史区域\",\"imageFingerprint\":{\"private\":true}}");
        row.setErrorCode("OPERATION_INTERRUPTED"); row.setRetryable(true);
        row.setTaskId(7L);
        RsTask task = new RsTask(); task.setId(7L); task.setOwnerId("owner"); task.setStatus("SUCCESS");
        when(tasks.selectByIdForOwner(7L,"owner")).thenReturn(task);
        when(store.find(id,"owner")).thenReturn(row);
        for (String status : List.of("RUNNING","NEEDS_INPUT","WAITING_CONFIRMATION","EXPIRED","WAITING_TASK",
                "READY_TO_EXPLAIN","EXPLAINING","SUCCEEDED","INSUFFICIENT_EVIDENCE","FAILED","UNSUPPORTED","CANCELED")) {
            row.setStatus(status);
            var result = service.get(id);
            assertThat(result.path("status").asText()).isEqualTo(status);
            assertThat(result.path("version").asInt()).isEqualTo(3);
            assertThat(result.path("draft").path("imageName").asText()).isEqualTo("历史影像");
            assertThat(result.path("draft").has("imageFingerprint")).isFalse();
            assertThat(result.path("task").path("status").asText()).isEqualTo("SUCCESS");
            assertThat(result.path("error").path("retryable").asBoolean()).isFalse();
        }
        verify(store, times(12)).find(id,"owner");
        verifyNoMoreInteractions(store);
        verifyNoInteractions(client,transactions);
    }

    @Test void otherOwnerCannotReadExecutionOrSteps() {
        when(users.getAuthenticatedUserId()).thenReturn("another");
        String id=UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(AgentFailure.class)
                .satisfies(error -> assertThat(((AgentFailure)error).errorCode).isEqualTo("EXECUTION_NOT_FOUND"));
        assertThatThrownBy(() -> service.steps(id,0)).isInstanceOf(AgentFailure.class);
        verify(store,never()).steps(anyString(),anyInt());
        verifyNoInteractions(client,transactions);
    }

    @Test void platformKnowledgeStillUsesOnlyTheCurrentAnswerOperation() {
        when(users.getAuthenticatedUserId()).thenReturn("owner");
        var request=object().put("schemaVersion",1).put("question","什么是NDVI？");
        var response=object().put("knowledgeIndexId","a".repeat(64)).put("answer","知识库中没有足够信息")
                .put("insufficientEvidence",true);
        response.set("claims",array()); response.set("citations",array());
        when(client.post("answers",request,Duration.ofSeconds(30))).thenReturn(response);
        assertThat(service.answer(object().put("question","什么是NDVI？")).path("insufficientEvidence").asBoolean()).isTrue();
        verify(client).post("answers",request,Duration.ofSeconds(30));
        verifyNoInteractions(store,transactions);
    }
}
