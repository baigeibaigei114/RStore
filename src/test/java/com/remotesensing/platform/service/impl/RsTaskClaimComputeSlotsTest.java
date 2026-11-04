package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.config.properties.RabbitTaskProperties;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.service.ImageBandCapabilityService;
import com.remotesensing.platform.service.MessageOutboxService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.MonitoringRegionService;
import java.time.Instant;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Real service/slot logic and Spring completion callbacks; SQL state is an in-memory adapter. */
class RsTaskClaimComputeSlotsTest {
    private static final String EXECUTOR = "11111111-1111-4111-8111-111111111111";
    private final RsTaskMapper tasks = mock(RsTaskMapper.class);
    private final RsTaskLogMapper logs = mock(RsTaskLogMapper.class);
    private final CompletionManager manager = new CompletionManager();
    private final SlotState store = new SlotState(manager);
    private ComputeSlots slots;
    private RsTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Worker-Protocol", "ingestion-v1");
        request.addHeader("X-Worker-Id", EXECUTOR);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var config = new IngestionProperties();
        config.setEnabled(true);
        slots = new ComputeSlots(store, config);
        service = new RsTaskServiceImpl(mock(RsImageMapper.class), tasks, logs, mock(RsResultFileMapper.class),
                new RabbitTaskProperties(), new MinioProperties(), new ObjectMapper(), mock(ImageBandCapabilityService.class),
                mock(MessageOutboxService.class), mock(GeoServerService.class), mock(MinioService.class), manager,
                mock(CurrentUserContext.class), mock(MonitoringRegionService.class));
        service.setIngestionSlots(slots);
    }

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void missingTaskRollbackReleasesPermitAndNextInspectionCanClaim() {
        assertThatThrownBy(() -> service.claim(999L)).isInstanceOf(BusinessException.class).hasMessageContaining("任务不存在");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(store.state).isEqualTo("IDLE");
        assertThat(slots.reserve("COMPUTE", "INSPECT:next", EXECUTOR, 300)).isNotNull();
        assertThat(store.holder).isEqualTo("INSPECT:next");
    }

    @Test
    void failureAfterAttemptWriteWithConfirmedRollbackAlsoReleasesPermit() {
        pendingTask();
        when(logs.insert(any())).thenThrow(new IllegalStateException("log write failed"));
        assertThatThrownBy(() -> service.claim(1L)).isInstanceOf(IllegalStateException.class).hasMessage("log write failed");
        verify(tasks).claimForRunning(eq(1L), any(), any(), anyInt());
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(store.state).isEqualTo("IDLE");
        assertThat(slots.reserve("COMPUTE", "INSPECT:next", EXECUTOR, 300)).isNotNull();
    }

    @Test
    void unknownCommitRetainsRecoveryFenceAndBlocksNextClaim() {
        pendingTask();
        manager.failCommit = true;
        assertThatThrownBy(() -> service.claim(1L)).isInstanceOf(TransactionSystemException.class);
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
        assertThat(store.state).isEqualTo("RECOVERY_REQUIRED");
        assertThat(slots.reserve("COMPUTE", "INSPECT:next", EXECUTOR, 300)).isNull();
    }

    @Test
    void failedRollbackDoesNotProveThePermitUnused() {
        manager.failRollback = true;
        assertThatThrownBy(() -> service.claim(999L)).isInstanceOf(TransactionSystemException.class);
        assertThat(store.state).isEqualTo("RECOVERY_REQUIRED");
        assertThat(slots.reserve("COMPUTE", "INSPECT:next", EXECUTOR, 300)).isNull();
    }

    @Test
    void stalePermitCannotReleaseNewerHolder() {
        var old = slots.reserve("COMPUTE", "NDVI:999", EXECUTOR, 900);
        slots.stopped("COMPUTE", "NDVI:999", EXECUTOR, old.fence());
        var next = slots.reserve("COMPUTE", "INSPECT:next", EXECUTOR, 300);
        slots.stopped("COMPUTE", "NDVI:999", EXECUTOR, old.fence());
        assertThat(next.fence()).isGreaterThan(old.fence());
        assertThat(store.state).isEqualTo("HELD");
        assertThat(store.holder).isEqualTo("INSPECT:next");
        assertThat(store.fence).isEqualTo(next.fence());
    }

    private void pendingTask() {
        var task = new RsTask();
        task.setId(1L);
        task.setStatus("PENDING");
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(tasks.claimForRunning(eq(1L), any(), any(), anyInt())).thenReturn(1);
    }

    /** Spring drives the real synchronization lifecycle; no JDBC or native execution occurs. */
    private static class CompletionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean failCommit;
        boolean failRollback;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit) throw new TransactionSystemException("Commit outcome unknown");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            if (failRollback) throw new TransactionSystemException("Rollback outcome unknown");
        }
    }

    /** Only the slot SQL state is substituted; comparisons use the real ComputeSlots predicates. */
    private static class SlotState extends IngestionStore {
        String state = "IDLE";
        String holder;
        String executor;
        long fence;
        SlotState(CompletionManager manager) { super(mock(DataSource.class), new ObjectMapper(), manager); }
        @Override public <T> T independent(Supplier<T> body) { return body.get(); }
        @Override public long count(String sql, Object... parameters) { return 0; }
        @Override public ObjectNode one(String sql, Object... parameters) {
            return new ObjectMapper().createObjectNode().put("state", state).put("fence", fence)
                    .put("hard_deadline", Instant.now().plusSeconds(900).toString());
        }
        @Override public int update(String sql, Object... p) {
            if (sql.contains("SET state='HELD'")) {
                state = "HELD"; holder = (String) p[0]; executor = (String) p[1]; fence++;
                return 1;
            }
            if (!java.util.Objects.equals(holder, p[1]) || !java.util.Objects.equals(executor, p[2])
                    || fence != ((Number) p[3]).longValue()) return 0;
            if (sql.contains("SET state='IDLE'")) { state = "IDLE"; holder = null; executor = null; }
            else if (sql.contains("SET state='RECOVERY_REQUIRED'")) state = "RECOVERY_REQUIRED";
            else throw new IllegalArgumentException("Unexpected slot update");
            return 1;
        }
    }
}
