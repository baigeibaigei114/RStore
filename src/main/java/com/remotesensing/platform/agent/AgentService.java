package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.remotesensing.platform.common.*;
import com.remotesensing.platform.dto.*;
import com.remotesensing.platform.entity.*;
import com.remotesensing.platform.mapper.*;
import com.remotesensing.platform.service.RsTaskService;
import java.time.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.remotesensing.platform.agent.AgentJson.*;

/** Bounded orchestration. All external calls happen outside execution/confirmation transactions. */
@Service
@Slf4j
public class AgentService {
    private final AgentMapper store;
    private final AgentResources resources;
    private final AgentClient client;
    private final RsTaskService tasks;
    private final RsTaskMapper taskMapper;
    private final RsResultFileMapper results;
    private final CurrentUserContext users;
    private final AgentProperties limits;
    private final TransactionTemplate tx;

    public AgentService(AgentMapper store, AgentResources resources, AgentClient client, RsTaskService tasks,
                        RsTaskMapper taskMapper, RsResultFileMapper results, CurrentUserContext users,
                        AgentProperties limits, PlatformTransactionManager manager) {
        this.store = store; this.resources = resources; this.client = client; this.tasks = tasks;
        this.taskMapper = taskMapper; this.results = results; this.users = users; this.limits = limits;
        this.tx = new TransactionTemplate(manager);
    }
    private record Claim(AgentExecution execution, AgentStep root, boolean fresh) {}

    public ObjectNode create(JsonNode body) {
        fields(body, "clientRequestId", "text");
        String key = uuid(body, "clientRequestId"), goal = text(body, "text", 2000); sensitive(goal);
        String owner = users.getAuthenticatedUserId(), digest = hash(body);
        Claim claim;
        try {
            claim = tx.execute(status -> {
                AgentExecution old = store.byCreateKey(owner, key);
                if (old != null) { sameHash(old.getCreateRequestHash(), digest); return new Claim(old, null, false); }
                AgentExecution e = new AgentExecution();
                e.setId(UUID.randomUUID().toString()); e.setOwnerId(owner); e.setCreateRequestId(key); e.setCreateRequestHash(digest);
                e.setStatus("NEEDS_INPUT"); e.setVersion(1); e.setIntentJson(object().put("goal", goal).toString());
                e.setCandidateJson(object().set("imageCandidates", array()).toString());
                store.insert(e);
                return begin(e, "DECISION", key, digest);
            });
        } catch (DuplicateKeyException e) {
            AgentExecution old = store.byCreateKey(owner, key);
            if (old == null) throw e;
            sameHash(old.getCreateRequestHash(), digest); claim = new Claim(old, null, false);
        }
        if (claim.fresh()) runDecision(claim);
        return get(claim.execution().getId());
    }

    public ObjectNode message(String id, JsonNode body) {
        fields(body, "clientRequestId", "expectedVersion", "text", "selectedImageId", "selectedRegionId");
        String key = uuid(body, "clientRequestId"); int version = integer(body, "expectedVersion", 1);
        String extra = optionalText(body, "text", 2000); sensitive(extra);
        require(extra != null || body.hasNonNull("selectedImageId") || body.hasNonNull("selectedRegionId"));
        String owner = users.getAuthenticatedUserId();
        Claim claim = tx.execute(status -> {
            AgentExecution e = lock(id, owner); refresh(e);
            AgentStep old = store.byRequest(e.getId(), key);
            if (old != null) { sameHash(old.getRequestHash(), hash(body)); return new Claim(e, old, false); }
            if (e.getTaskId() != null || !Set.of("NEEDS_INPUT", "WAITING_CONFIRMATION", "EXPIRED").contains(e.getStatus()))
                throw AgentFailure.conflict("STATE_CONFLICT");
            expected(e, version);
            ObjectNode intent = (ObjectNode) parse(e.getIntentJson());
            if (extra != null) {
                String goal = intent.path("goal").asText() + "\n补充：" + extra;
                require(goal.codePointCount(0, goal.length()) <= 2000); intent.put("goal", goal);
            }
            ObjectNode candidates = (ObjectNode) parse(e.getCandidateJson());
            resources.select(candidates, body, owner);
            candidates.remove(List.of("question", "missingFields"));
            e.setCandidateJson(candidates.toString()); e.setIntentJson(intent.toString());
            return begin(e, "DECISION", key, hash(body));
        });
        if (claim.fresh()) runDecision(claim);
        return get(id);
    }

    public ObjectNode confirm(String id, JsonNode body) {
        fields(body, "draftVersion", "draftHash");
        int draftVersion = integer(body, "draftVersion", 1); String draftHash = hashField(body, "draftHash");
        String owner = users.getAuthenticatedUserId();
        AgentFailure failure = tx.execute(status -> {
            AgentExecution e = lock(id, owner);
            // Repeat confirmation precedes resource status/expiry checks; the image is now PROCESSING.
            if (e.getTaskId() != null) {
                if (!Objects.equals(e.getConfirmedVersion(), draftVersion) || !Objects.equals(e.getConfirmedHash(), draftHash))
                    throw AgentFailure.conflict("CONFIRMATION_CONFLICT");
                return null;
            }
            if (!"WAITING_CONFIRMATION".equals(e.getStatus()) || e.getDraftVersion() != draftVersion || !Objects.equals(e.getDraftHash(), draftHash))
                throw AgentFailure.conflict("DRAFT_STALE");
            if (e.getDraftExpiresAt() == null || !e.getDraftExpiresAt().isAfter(store.now())) {
                expireDraft(e); save(e); return AgentFailure.conflict("DRAFT_EXPIRED");
            }
            JsonNode draft = parse(e.getDraftJson());
            RsImage image;
            com.remotesensing.platform.vo.MonitoringRegionVO region;
            try {
                image = resources.image(AgentJson.id(draft, "imageId"), owner, true);
                region = resources.region(AgentJson.id(draft, "monitoringRegionId"), owner, true);
                if (!resources.imageFingerprint(image).equals(draft.path("imageFingerprint"))
                        || region.getVersion() != draft.path("regionVersion").intValue()
                        || !hash(parse(region.getGeometryJson())).equals(draft.path("regionGeometrySha256").asText())
                        || !resources.draftHash(draft).equals(draftHash)) throw AgentFailure.conflict("DRAFT_STALE");
            } catch (com.remotesensing.platform.exception.BusinessException ex) {
                expireDraft(e); save(e); return AgentFailure.conflict("DRAFT_STALE");
            }
            RsTaskSubmitDTO submit = new RsTaskSubmitDTO();
            submit.setImageId(image.getId()); submit.setMonitoringRegionId(region.getId());
            submit.setTaskType(RemoteSensingTaskMessage.TaskType.NDVI);
            submit.setParams(new LinkedHashMap<>(Map.of("redBand", 3, "nirBand", 4)));
            submit.setClientRequestId("agent:" + e.getId() + ":v" + draftVersion);
            ObjectNode regionSnapshot = resources.regionSnapshot(region);
            RsTask task = tasks.prepareSubmissionInCurrentTransaction(submit, image, regionSnapshot);
            JsonNode prepared = parse(task.getParams());
            require(hash(prepared.path("regionSnapshot")).equals(hash(regionSnapshot)));
            require(prepared.path("inputSnapshot").path("manifest").path("sha256").asText()
                    .equals(draft.path("imageFingerprint").path("inputSHA256").asText()));
            require(prepared.path("redBand").intValue() == 3 && prepared.path("nirBand").intValue() == 4);
            e.setConfirmedVersion(draftVersion); e.setConfirmedHash(draftHash); e.setTaskId(task.getId());
            e.setStatus("WAITING_TASK"); clearError(e); save(e);
            return null;
        });
        // Stale state was committed; throwing inside that transaction would erase the invalidation.
        if (failure != null) throw failure;
        return get(id);
    }

    public ObjectNode explanation(String id, JsonNode body) {
        fields(body, "clientRequestId"); String key = uuid(body, "clientRequestId");
        Claim claim = tx.execute(status -> {
            AgentExecution e = lock(id, users.getAuthenticatedUserId()); refresh(e);
            AgentStep old = store.byRequest(e.getId(), key);
            if (old != null) { sameHash(old.getRequestHash(), hash(body)); return new Claim(e, old, false); }
            if (Set.of("SUCCEEDED", "INSUFFICIENT_EVIDENCE", "EXPLAINING").contains(e.getStatus())) return new Claim(e, null, false);
            if (!"READY_TO_EXPLAIN".equals(e.getStatus())) throw AgentFailure.conflict("TASK_NOT_READY");
            return begin(e, "EXPLANATION", key, hash(body));
        });
        if (claim.fresh()) runExplanation(claim);
        return get(id);
    }
    public ObjectNode retry(String id, JsonNode body) {
        fields(body, "clientRequestId", "expectedVersion");
        String key = uuid(body, "clientRequestId"); int version = integer(body, "expectedVersion", 1);
        Claim claim = tx.execute(status -> {
            AgentExecution e = lock(id, users.getAuthenticatedUserId()); refresh(e);
            AgentStep old = store.byRequest(e.getId(), key);
            if (old != null) { sameHash(old.getRequestHash(), hash(body)); return new Claim(e, old, false); }
            expected(e, version);
            if (!"FAILED".equals(e.getStatus()) || !canRetry(e)) throw AgentFailure.conflict("RETRY_NOT_ALLOWED");
            return begin(e, e.getTaskId() == null ? "DECISION" : "EXPLANATION", key, hash(body));
        });
        if (claim.fresh()) { if (claim.execution().getTaskId() == null) runDecision(claim); else runExplanation(claim); }
        return get(id);
    }
    public ObjectNode cancel(String id, JsonNode body) {
        fields(body, "expectedVersion"); int version = integer(body, "expectedVersion", 1);
        tx.executeWithoutResult(status -> {
            AgentExecution e = lock(id, users.getAuthenticatedUserId()); expected(e, version);
            if (e.getTaskId() != null || Set.of("UNSUPPORTED", "CANCELED").contains(e.getStatus())) throw AgentFailure.conflict("CANCEL_NOT_ALLOWED");
            if (e.getOperationId() != null) store.interruptSteps(e.getId(), e.getOperationId());
            e.setOperationId(null); e.setStatus("CANCELED"); e.setDraftJson(null); e.setDraftHash(null); clearError(e); save(e);
        });
        return get(id);
    }
    public ObjectNode get(String id) {
        AgentExecution e = tx.execute(status -> { AgentExecution row = lock(id, users.getAuthenticatedUserId()); refresh(row); return row; });
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
        return AgentProtocol.generated(output, false);
    }
    /** Startup only converges at most 100 already-expired operations; never calls the provider. */
    public void recoverExpired() {
        for (AgentExecution row : store.expiredOperations()) tx.executeWithoutResult(status -> {
            AgentExecution e = store.lock(row.getId(), row.getOwnerId()); if (e != null) refresh(e);
        });
    }

    private Claim begin(AgentExecution e, String kind, String key, String digest) {
        if (e.getOperationId() != null) throw AgentFailure.conflict("OPERATION_RUNNING");
        e.setOperationId(UUID.randomUUID().toString()); e.setOperationKind(kind); e.setOperationVersion(e.getVersion() + 1);
        e.setOperationDeadline(store.now().plusSeconds(limits.getOperationSeconds()));
        e.setStatus(kind.equals("DECISION") ? "RUNNING" : "EXPLAINING"); clearError(e);
        if (kind.equals("DECISION")) { e.setDraftVersion(e.getDraftVersion() + 1); e.setDraftJson(null); e.setDraftHash(null); e.setDraftExpiresAt(null); }
        save(e);
        AgentStep root = step(e, "OPERATION", kind, object()); root.setClientRequestId(key); root.setRequestHash(digest); store.insertStep(root);
        return new Claim(e, root, true);
    }
    private void runDecision(Claim claim) {
        AgentExecution original = claim.execution();
        ObjectNode context = (ObjectNode) parse(original.getCandidateJson()); Set<String> seenTools = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(limits.getRoundSeconds()).toNanos();
        try {
            for (int turn = 0; turn < limits.getRoundModelCalls(); turn++) {
                ObjectNode payload = object().put("schemaVersion", 1).put("operationId", original.getOperationId())
                        .put("goal", parse(original.getIntentJson()).path("goal").asText());
                payload.set("selectionContext", resources.pythonContext(context)); payload.set("observations", array());
                AgentExecution current = store.find(original.getId(), original.getOwnerId());
                payload.put("remainingCalls", Math.max(1, Math.min(12, limits.getTotalModelCalls()) - current.getModelCallCount()));
                JsonNode response = modelCall(claim, "decisions", payload, deadline);
                JsonNode action = AgentProtocol.action(response);
                if (action.path("kind").asText().equals("CALL_TOOL")) {
                    String signature = hash(action);
                    if (!seenTools.add(signature) || seenTools.size() > limits.getRoundToolCalls()) break;
                    AgentStep toolStep = reserve(claim, "TOOL", action.path("tool").asText(), action.path("arguments"));
                    try {
                        context = resources.tool(action.path("tool").asText(), action.path("arguments"), original.getOwnerId(), context);
                        finishCall(claim, toolStep, object().put("imageCount", context.path("imageCandidates").size())
                                .put("regionCount", context.path("regionCandidates").size()), null, null);
                    } catch (RuntimeException error) {
                        AgentFailure safe = safeFailure(error); finishCall(claim, toolStep, object(), safe, null); throw safe;
                    }
                    continue;
                }
                ObjectNode finalContext = context;
                complete(claim, e -> {
                    e.setCandidateJson(finalContext.toString());
                    switch (action.path("kind").asText()) {
                        case "PROPOSE_NDVI" -> {
                            ObjectNode draft = resources.draft(e, action, finalContext);
                            e.setDraftJson(draft.toString()); e.setDraftHash(resources.draftHash(draft));
                            e.setDraftExpiresAt(store.now().plusMinutes(limits.getDraftMinutes())); e.setStatus("WAITING_CONFIRMATION");
                        }
                        case "ASK_USER" -> {
                            finalContext.set("question", action.path("question")); finalContext.set("missingFields", action.path("missingFields"));
                            e.setCandidateJson(finalContext.toString()); e.setStatus("NEEDS_INPUT");
                        }
                        case "UNSUPPORTED" -> {
                            finalContext.set("unsupportedReason", action.path("message"));
                            e.setCandidateJson(finalContext.toString()); e.setStatus("UNSUPPORTED");
                        }
                        default -> throw AgentProtocol.invalid();
                    }
                });
                return;
            }
            ObjectNode finalContext = context;
            finalContext.put("question", "本轮查询已达到上限，请明确选择候选影像和区域或补充需求");
            finalContext.set("missingFields", array().add("imageId").add("monitoringRegionId"));
            complete(claim, e -> { e.setCandidateJson(finalContext.toString()); e.setStatus("NEEDS_INPUT"); });
        } catch (RuntimeException error) { fail(claim, safeFailure(error)); }
    }

    private void runExplanation(Claim claim) {
        try {
            AgentExecution e = claim.execution();
            JsonNode metadata = metadata(e);
            JsonNode draft = parse(e.getDraftJson());
            // Use the confirmed input snapshot rather than a mutable current image summary.
            ObjectNode image = object().put("sensor", "Sentinel-2").put("captureTime", draft.path("captureTime").asText(null));
            ObjectNode payload = object().put("schemaVersion", 1).put("operationId", e.getOperationId()).put("taskType", "NDVI")
                    .put("promptVersion", "ndvi-explanation-v1");
            payload.set("resultMetadata", metadata); payload.set("publicImageSummary", image);
            JsonNode response = modelCall(claim, "explanations", payload,
                    System.nanoTime() + Duration.ofSeconds(limits.getRoundSeconds()).toNanos());
            ObjectNode generated = AgentProtocol.generated(response, true);
            String metadataHash = hash(metadata);
            generated.put("taskId", e.getTaskId().toString()); generated.put("resultMetadataSHA", metadataHash);
            generated.set("statistics", metadata.path("statistics").deepCopy());
            generated.set("bandMapping", metadata.path("bandMapping").deepCopy());
            generated.set("coverageRatio", metadata.path("coverageRatio"));
            generated.put("schemaVersion", 2).put("scope", "monitoring_region").put("algorithmVersion", "ndvi-v1")
                    .put("regionVersion", metadata.path("regionSnapshot").path("version").intValue());
            generated.set("platformLimitations", array().add("准入校验不证明科学真值").add("有效像素比例不是植被覆盖率")
                    .add("单期均值不能独立判断作物健康、因果或多期趋势"));
            complete(claim, row -> {
                require(hash(metadata(row)).equals(metadataHash));
                row.setExplanationJson(generated.toString()); row.setResultMetadataSha256(metadataHash);
                row.setKnowledgeIndexId(generated.path("knowledgeIndexId").asText());
                row.setExplanationKey(sha(row.getTaskId() + ":" + metadataHash + ":" + row.getKnowledgeIndexId() + ":ndvi-explanation-v1:" + limits.getModel()));
                row.setStatus(generated.path("insufficientEvidence").booleanValue() ? "INSUFFICIENT_EVIDENCE" : "SUCCEEDED");
            });
        } catch (RuntimeException error) { fail(claim, safeFailure(error)); }
    }

    private JsonNode modelCall(Claim claim, String kind, JsonNode payload, long deadline) {
        AgentStep step = reserve(claim, "MODEL", kind, object().put("inputBytes", payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        JsonNode response = null;
        try {
            response = client.post(kind, payload, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            // Validation remains inside this accounting boundary so rejected output is a failed step.
            if (kind.equals("decisions")) AgentProtocol.action(response); else AgentProtocol.generated(response, true);
            ObjectNode summary = object();
            if (kind.equals("decisions")) summary.put("actionKind", response.path("action").path("kind").asText());
            else summary.put("insufficientEvidence", response.path("insufficientEvidence").asBoolean()).put("claimCount", response.path("claims").size());
            finishCall(claim, step, summary, null, AgentProtocol.trace(response));
            return response;
        } catch (RuntimeException error) {
            AgentFailure failure = safeFailure(error);
            JsonNode trace = response == null ? failure.trace : AgentProtocol.trace(response);
            finishCall(claim, step, object(), failure, trace); throw failure;
        }
    }
    private AgentStep reserve(Claim claim, String kind, String name, JsonNode input) {
        return tx.execute(status -> {
            AgentExecution e = active(claim);
            if (kind.equals("MODEL")) {
                if (e.getModelCallCount() >= Math.min(12, limits.getTotalModelCalls())) throw budget();
                e.setModelCallCount(e.getModelCallCount() + 1);
            } else {
                if (e.getToolCallCount() >= Math.min(8, limits.getTotalToolCalls())) throw budget();
                e.setToolCallCount(e.getToolCallCount() + 1);
            }
            save(e); AgentStep step = step(e, kind, name, input); store.insertStep(step); return step;
        });
    }
    private AgentStep step(AgentExecution e, String kind, String name, JsonNode input) {
        AgentStep step = new AgentStep(); step.setId(UUID.randomUUID().toString()); step.setExecutionId(e.getId());
        step.setOperationId(e.getOperationId()); step.setSequence(store.nextSequence(e.getId())); step.setAttempt(1);
        step.setKind(kind); step.setName(name); step.setStatus("RUNNING"); step.setStartedAt(store.now()); step.setSafeInputJson(input.toString());
        return step;
    }
    private void finishCall(Claim claim, AgentStep step, JsonNode summary, AgentFailure error, JsonNode trace) {
        tx.executeWithoutResult(status -> {
            AgentExecution e = lock(claim.execution().getId(), claim.execution().getOwnerId());
            boolean valid = operationMatches(e, claim) && e.getOperationDeadline().isAfter(store.now());
            step.setStatus(valid ? error == null ? "SUCCESS" : "FAILED" : "INTERRUPTED"); step.setEndedAt(store.now());
            step.setDurationMs(Math.max(0, Duration.between(step.getStartedAt(), step.getEndedAt()).toMillis()));
            // Invalidated operations may record cost, but never publish their late business output.
            step.setSafeOutputJson(valid ? summary.toString() : null);
            if (error != null) { step.setErrorCode(error.errorCode); step.setSafeErrorMessage(error.getMessage()); }
            if (trace != null) {
                JsonNode safe = AgentProtocol.trace(trace);
                step.setProvider("deepseek"); step.setModelRequested(safe.path("modelRequested").asText(null));
                step.setModelReturned(safe.path("modelReturned").asText(null)); step.setUsageJson(safe.path("usage").toString());
            }
            int changed = store.finishStep(step);
            if (changed == 0 && !valid) changed = store.accountInterruptedStep(step);
            if (changed != 1) {
                log.warn("Agent step accounting conflict: execution={}, operation={}, step={}", step.getExecutionId(), step.getOperationId(), step.getId());
                throw new IllegalStateException("Agent step accounting state conflict");
            }
        });
    }
    private void complete(Claim claim, java.util.function.Consumer<AgentExecution> update) {
        tx.executeWithoutResult(status -> {
            AgentExecution e = active(claim); update.accept(e); e.setOperationId(null); clearError(e); save(e);
            finishRoot(claim, null);
        });
    }
    private void fail(Claim claim, AgentFailure failure) {
        tx.executeWithoutResult(status -> {
            AgentExecution e = lock(claim.execution().getId(), claim.execution().getOwnerId());
            if (!operationMatches(e, claim)) return;
            AgentFailure recorded = failure;
            if (!e.getOperationDeadline().isAfter(store.now())) {
                store.interruptSteps(e.getId(), e.getOperationId());
                recorded = new AgentFailure("OPERATION_INTERRUPTED", "操作中断，请显式重试", 503, true);
            }
            e.setStatus("FAILED"); e.setErrorCode(recorded.errorCode); e.setSafeErrorMessage(recorded.getMessage()); e.setRetryable(recorded.retryable);
            e.setOperationId(null); save(e); finishRoot(claim, recorded);
        });
    }
    private void finishRoot(Claim claim, AgentFailure failure) {
        AgentStep root = claim.root(); root.setStatus(failure == null ? "SUCCESS" : "FAILED"); root.setEndedAt(store.now());
        root.setDurationMs(Math.max(0, Duration.between(root.getStartedAt(), root.getEndedAt()).toMillis()));
        if (failure != null) { root.setErrorCode(failure.errorCode); root.setSafeErrorMessage(failure.getMessage()); }
        store.finishStep(root);
    }
    private AgentExecution active(Claim claim) {
        AgentExecution e = lock(claim.execution().getId(), claim.execution().getOwnerId());
        if (!operationMatches(e, claim) || !e.getOperationDeadline().isAfter(store.now())) throw AgentFailure.conflict("OPERATION_INTERRUPTED");
        return e;
    }
    private boolean operationMatches(AgentExecution e, Claim claim) {
        return Objects.equals(e.getOperationId(), claim.execution().getOperationId()) && e.getOperationId() != null
                && Objects.equals(e.getOperationVersion(), claim.execution().getOperationVersion());
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
            e.setErrorCode("OPERATION_INTERRUPTED"); e.setSafeErrorMessage("操作中断，请显式重试"); e.setRetryable(true); changed = true;
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
                .put("kind", e.getOperationKind()).put("deadline", e.getOperationDeadline().toString()));
        value.putNull("draft"); value.putNull("task"); value.putNull("error"); value.set("explanation", parse(e.getExplanationJson()));
        value.set("budget", object().put("modelCalls", e.getModelCallCount()).put("toolCalls", e.getToolCallCount()));
        if ("WAITING_CONFIRMATION".equals(e.getStatus())) {
            JsonNode draft = parse(e.getDraftJson()); ObjectNode safe = object().put("draftHash", e.getDraftHash()).put("expiresAt", e.getDraftExpiresAt().toString());
            for (String field : List.of("draftVersion", "imageId", "imageName", "captureTime", "monitoringRegionId", "regionName", "regionVersion", "taskType", "params", "operationLabel")) safe.set(field, draft.path(field));
            value.set("draft", safe);
        }
        if (e.getTaskId() != null) {
            RsTask task = taskMapper.selectByIdForOwner(e.getTaskId(), e.getOwnerId());
            if (task != null) value.set("task", object().put("taskId", task.getId().toString()).put("status", task.getStatus()).put("progress", task.getProgress()));
        }
        if (e.getErrorCode() != null) value.set("error", object().put("errorCode", e.getErrorCode()).put("message", e.getSafeErrorMessage()).put("retryable", canRetry(e)));
        return value;
    }
    private AgentExecution lock(String id, String owner) { AgentExecution e = store.lock(validId(id), owner); if (e == null) throw missing(); return e; }
    private String validId(String id) { return uuid(object().put("id", id), "id"); }
    private AgentFailure missing() { return new AgentFailure("EXECUTION_NOT_FOUND", "执行不存在", 404, false); }
    private void save(AgentExecution e) { int expected = e.getVersion(); e.setVersion(expected + 1); if (store.save(e, expected) != 1) throw AgentFailure.conflict("VERSION_CONFLICT"); }
    private void expected(AgentExecution e, int expected) { if (e.getVersion() != expected) throw AgentFailure.conflict("VERSION_CONFLICT"); }
    private void sameHash(String old, String current) { if (!Objects.equals(old, current)) throw AgentFailure.conflict("REQUEST_KEY_CONFLICT"); }
    private void expireDraft(AgentExecution e) { e.setStatus("EXPIRED"); e.setDraftJson(null); e.setDraftHash(null); }
    private void clearError(AgentExecution e) { e.setErrorCode(null); e.setSafeErrorMessage(null); e.setRetryable(false); }
    private AgentFailure budget() { return new AgentFailure("BUDGET_EXHAUSTED", "执行调用预算已耗尽，请新建明确意图", 429, false); }
    private boolean canRetry(AgentExecution e) {
        return "FAILED".equals(e.getStatus()) && (e.isRetryable() || "MODEL_OUTPUT_INVALID".equals(e.getErrorCode()));
    }
    private AgentFailure safeFailure(RuntimeException error) {
        return error instanceof AgentFailure failure ? failure : new AgentFailure("OPERATION_FAILED", "操作失败，请查看安全步骤后重试", 503, true);
    }
}
