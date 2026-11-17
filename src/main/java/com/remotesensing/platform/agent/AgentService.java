package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.remotesensing.platform.common.*;
import com.remotesensing.platform.entity.*;
import com.remotesensing.platform.mapper.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.remotesensing.platform.agent.AgentJson.*;

/** Saved execution history and the current platform-knowledge entry point. */
@Service
public class AgentService {
    private final AgentMapper store;
    private final AgentClient client;
    private final RsTaskMapper taskMapper;
    private final RsResultFileMapper results;
    private final CurrentUserContext users;
    private final AgentProperties limits;
    private final TransactionTemplate tx;

    public AgentService(AgentMapper store, AgentClient client, RsTaskMapper taskMapper,
                        RsResultFileMapper results, CurrentUserContext users,
                        AgentProperties limits, PlatformTransactionManager manager) {
        this.store = store; this.client = client; this.taskMapper = taskMapper; this.results = results;
        this.users = users; this.limits = limits; this.tx = new TransactionTemplate(manager);
    }
    public ObjectNode get(String id) {
        AgentExecution e = store.find(validId(id), users.getAuthenticatedUserId());
        if (e == null) throw missing();
        return view(e);
    }
    public ObjectNode page(int page, int size) {
        require(page >= 1 && page <= 100000 && size >= 1 && size <= 20);
        String owner = users.getAuthenticatedUserId(); ArrayNode records = array();
        for (AgentExecution e : store.page(owner, (page - 1) * size, size)) records.add(view(e));
        return object().put("pageNum", page).put("pageSize", size).put("total", store.count(owner)).set("records", records);
    }
    public ArrayNode steps(String id, int after) {
        require(after >= 0); String owner = users.getAuthenticatedUserId();
        if (store.find(validId(id), owner) == null) throw missing();
        ArrayNode output = array();
        for (AgentStep step : store.steps(id, after)) {
            ObjectNode value = object().put("sequence", step.getSequence()).put("kind", step.getKind()).put("name", step.getName())
                    .put("status", step.getStatus()).put("operationId", step.getOperationId()).put("errorCode", step.getErrorCode())
                    .put("modelRequested", step.getModelRequested()).put("modelReturned", step.getModelReturned());
            value.set("usage", parse(step.getUsageJson())); value.set("input", parse(step.getSafeInputJson()));
            value.set("output", parse(step.getSafeOutputJson()));
            value.put("startedAt", step.getStartedAt().toString()); value.put("endedAt", step.getEndedAt() == null ? null : step.getEndedAt().toString());
            if (step.getDurationMs() != null) value.put("durationMs", step.getDurationMs());
            output.add(value);
        }
        return output;
    }
    public ObjectNode answer(JsonNode body) {
        fields(body, "question"); String question = text(body, "question", 2000); sensitive(question); users.getAuthenticatedUserId();
        JsonNode output = client.post("answers", object().put("schemaVersion", 1).put("question", question), Duration.ofSeconds(limits.getRoundSeconds()));
        return AgentProtocol.generated(output);
    }
    /** Startup only converges at most 100 already-expired operations; never calls the provider. */
    public void recoverExpired() {
        for (AgentExecution row : store.expiredOperations()) tx.executeWithoutResult(status -> {
            AgentExecution e = store.lock(row.getId(), row.getOwnerId()); if (e != null) refresh(e);
        });
    }

    private JsonNode metadata(AgentExecution e) {
        RsTask task = taskMapper.selectByIdForOwner(e.getTaskId(), e.getOwnerId());
        if (task == null || !e.getOwnerId().equals(task.getOwnerId()) || !"SUCCESS".equals(task.getStatus()) || !"NDVI".equals(task.getTaskType()))
            throw new AgentFailure("TASK_FAILED", "任务未成功，不能生成解释", 409, false);
        RsResultFile result = results.selectByTaskId(e.getTaskId());
        if (result == null || result.getResultMetadata() == null) throw new AgentFailure("STATISTICS_MISSING", "结果统计缺失，不能生成解释", 409, false);
        try {
            require(e.getOwnerId().equals(result.getOwnerId()) && e.getTaskId().equals(result.getTaskId())
                    && Objects.equals(task.getImageId(), result.getImageId()));
            JsonNode metadata = parse(result.getResultMetadata());
            if (metadata.isNull() || metadata.isMissingNode()) throw new AgentFailure("STATISTICS_MISSING", "结果统计缺失", 409, false);
            NdviMetadataValidator.validate(metadata, task.getTaskType()); NdviMetadataValidator.validateTaskRegion(metadata, task.getParams());
            JsonNode taskParams = parse(task.getParams());
            require(taskParams.path("inputSnapshot").path("imageId").isIntegralNumber()
                    && taskParams.path("inputSnapshot").path("imageId").longValue() == task.getImageId());
            require(taskParams.path("inputSnapshot").path("manifest").path("sha256").asText()
                    .equals(parse(e.getDraftJson()).path("imageFingerprint").path("inputSHA256").asText()));
            require(integer(metadata, "schemaVersion", 1) == 2 && metadata.path("scope").asText().equals("monitoring_region"));
            require(integer(metadata.path("bandMapping"), "redBand", 1) == 3 && integer(metadata.path("bandMapping"), "nirBand", 1) == 4);
            return metadata;
        } catch (com.remotesensing.platform.exception.BusinessException error) {
            if (error instanceof AgentFailure failure && failure.errorCode.equals("STATISTICS_MISSING")) throw failure;
            throw new AgentFailure("STATISTICS_INVALID", "任务统计或区域快照不合法", 409, false);
        }
    }
    private void refresh(AgentExecution e) {
        OffsetDateTime now = store.now(); boolean changed = false;
        if (e.getOperationId() != null && !e.getOperationDeadline().isAfter(now)) {
            store.interruptSteps(e.getId(), e.getOperationId()); e.setOperationId(null); e.setStatus("FAILED");
            e.setErrorCode("OPERATION_INTERRUPTED"); e.setSafeErrorMessage("旧操作已中断，可从工作台开始新的分析"); e.setRetryable(false); changed = true;
        }
        if (e.getStatus().equals("WAITING_CONFIRMATION") && !e.getDraftExpiresAt().isAfter(now)) { expireDraft(e); changed = true; }
        if (e.getTaskId() != null && Set.of("WAITING_TASK", "READY_TO_EXPLAIN").contains(e.getStatus())) {
            RsTask task = taskMapper.selectByIdForOwner(e.getTaskId(), e.getOwnerId());
            if (task == null || Set.of("FAILED", "CANCELED").contains(task.getStatus())) {
                e.setStatus("FAILED"); e.setErrorCode("TASK_FAILED"); e.setSafeErrorMessage("遥感任务失败或不可访问"); e.setRetryable(false); changed = true;
            } else if ("SUCCESS".equals(task.getStatus())) {
                try { metadata(e); if (!e.getStatus().equals("READY_TO_EXPLAIN")) { e.setStatus("READY_TO_EXPLAIN"); changed = true; } }
                catch (AgentFailure failure) { e.setStatus("FAILED"); e.setErrorCode(failure.errorCode); e.setSafeErrorMessage(failure.getMessage()); e.setRetryable(false); changed = true; }
            }
        }
        if (changed) save(e);
    }
    private ObjectNode view(AgentExecution e) {
        JsonNode candidates = parse(e.getCandidateJson());
        ObjectNode value = object().put("executionId", e.getId()).put("version", e.getVersion()).put("status", e.getStatus())
                .put("question", candidates.path("question").asText(null)).put("hasMoreCandidates", candidates.path("hasMoreCandidates").asBoolean());
        value.put("unsupportedReason", "UNSUPPORTED".equals(e.getStatus()) ? candidates.path("unsupportedReason").asText(null) : null);
        value.set("imageCandidates", candidates.path("imageCandidates").isArray() ? candidates.path("imageCandidates") : array());
        value.set("regionCandidates", candidates.path("regionCandidates").isArray() ? candidates.path("regionCandidates") : array());
        value.set("missingFields", candidates.path("missingFields").isArray() ? candidates.path("missingFields") : array());
        value.set("operation", e.getOperationId() == null ? NullNode.instance : object().put("operationId", e.getOperationId())
                .put("kind", e.getOperationKind()).put("deadline", e.getOperationDeadline() == null ? null : e.getOperationDeadline().toString()));
        value.putNull("draft"); value.putNull("task"); value.putNull("error"); value.set("explanation", parse(e.getExplanationJson()));
        value.set("budget", object().put("modelCalls", e.getModelCallCount()).put("toolCalls", e.getToolCallCount()));
        if (e.getDraftJson() != null) {
            JsonNode draft = parse(e.getDraftJson()); ObjectNode safe = object().put("draftHash", e.getDraftHash()).put("expiresAt", e.getDraftExpiresAt() == null ? null : e.getDraftExpiresAt().toString());
            for (String field : List.of("draftVersion", "imageId", "imageName", "captureTime", "monitoringRegionId", "regionName", "regionVersion", "taskType", "params", "operationLabel")) safe.set(field, draft.path(field));
            value.set("draft", safe);
        }
        if (e.getTaskId() != null) {
            RsTask task = taskMapper.selectByIdForOwner(e.getTaskId(), e.getOwnerId());
            if (task != null) value.set("task", object().put("taskId", task.getId().toString()).put("status", task.getStatus()).put("progress", task.getProgress()));
        }
        if (e.getErrorCode() != null) value.set("error", object().put("errorCode", e.getErrorCode()).put("message", e.getSafeErrorMessage()).put("retryable", false));
        return value;
    }
    private String validId(String id) { return uuid(object().put("id", id), "id"); }
    private AgentFailure missing() { return new AgentFailure("EXECUTION_NOT_FOUND", "执行不存在", 404, false); }
    private void save(AgentExecution e) { int expected = e.getVersion(); e.setVersion(expected + 1); if (store.save(e, expected) != 1) throw AgentFailure.conflict("VERSION_CONFLICT"); }
    private void expireDraft(AgentExecution e) { e.setStatus("EXPIRED"); e.setDraftJson(null); e.setDraftHash(null); }
}
