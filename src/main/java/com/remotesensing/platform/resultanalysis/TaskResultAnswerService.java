package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentClient;
import com.remotesensing.platform.agent.AgentFailure;
import com.remotesensing.platform.agent.AgentProperties;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.NdviMetadataValidator;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RateLimitService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TaskResultAnswerService {
    private final CurrentUserContext users;
    private final RsTaskMapper tasks;
    private final RsResultFileMapper results;
    private final TaskResultAnswerStore store;
    private final AgentClient agent;
    private final AgentProperties limits;
    private final RateLimitService rate;

    TaskResultAnswerService(CurrentUserContext users, RsTaskMapper tasks, RsResultFileMapper results,
                            TaskResultAnswerStore store, AgentClient agent, AgentProperties limits,
                            RateLimitService rate) {
        this.users = users;
        this.tasks = tasks;
        this.results = results;
        this.store = store;
        this.agent = agent;
        this.limits = limits;
        this.rate = rate;
    }

    public ObjectNode get(long taskId) {
        String owner = users.getAuthenticatedUserId();
        Snapshot snapshot = snapshot(owner, taskId);
        ObjectNode state = object().put("resultVersion", snapshot.version()).put("canAsk", limits.isEnabled());
        if (!limits.isEnabled()) return state.put("message", "结果解读服务暂未启用");
        return state.set("turn", view(store.latest(owner, taskId, snapshot.version())));
    }

    public ObjectNode ask(long taskId, JsonNode body) {
        fields(body, "requestId", "resultVersion", "question");
        UUID requestId = UUID.fromString(uuid(body, "requestId"));
        String version = hashField(body, "resultVersion");
        String question = text(body, "question", 500).strip();
        sensitive(question);
        String owner = users.getAuthenticatedUserId();
        Snapshot snapshot = snapshot(owner, taskId);
        if (!snapshot.version().equals(version)) throw AgentFailure.conflict("RESULT_CHANGED");
        if (!limits.isEnabled()) throw new AgentFailure("MODEL_UNAVAILABLE", "结果解读服务暂未启用", 503, true);

        TaskResultAnswerStore.Turn existing = store.find(owner, taskId, requestId);
        if (existing != null) return replay(existing, version, question);
        if (!store.reserve(owner, taskId, requestId, version, question, limits.getOperationSeconds())) {
            return replay(store.find(owner, taskId, requestId), version, question);
        }

        // The durable reservation owns the only model call. There is no DB transaction around HTTP.
        try {
            rate.check("agent:" + owner, limits.getOwnerRateLimit(), Duration.ofSeconds(limits.getOwnerRateSeconds()));
            JsonNode selection = agent.post("result-answers", object().put("question", question),
                    Duration.ofSeconds(limits.getRoundSeconds()));
            ObjectNode answer = answer(selection, snapshot);
            if (!snapshot(owner, taskId).version().equals(version)) throw AgentFailure.conflict("RESULT_CHANGED");
            store.complete(owner, taskId, requestId, answer.toString());
        } catch (BusinessException error) {
            String message = error.getCode() == 409 ? "任务结果已变化，请刷新后重新提问"
                    : error.getCode() == 429 ? "提问过于频繁，请稍后重试" : "本次解读未完成，可以重新提问";
            store.fail(owner, taskId, requestId, message);
            if (error.getCode() == 404 || error.getCode() == 409) throw error;
        }
        return replay(store.find(owner, taskId, requestId), version, question);
    }

    private Snapshot snapshot(String owner, long taskId) {
        RsTask task = tasks.selectByIdForOwner(taskId, owner);
        if (task == null || !owner.equals(task.getOwnerId()))
            throw new AgentFailure("TASK_NOT_FOUND", "任务不存在或不可访问", 404, false);
        if (!"SUCCESS".equals(task.getStatus()) || !"NDVI".equals(task.getTaskType()))
            throw new AgentFailure("RESULT_UNAVAILABLE", "区域 NDVI 任务成功后可解读", 409, false);

        RsResultFile file = results.selectByTaskId(taskId);
        if (file == null || !owner.equals(file.getOwnerId()) || !Objects.equals(file.getTaskId(), taskId)
                || !Objects.equals(file.getImageId(), task.getImageId()) || file.getId() == null)
            throw new AgentFailure("RESULT_UNAVAILABLE", "没有可核对的任务结果", 409, false);
        try {
            JsonNode metadata = parse(file.getResultMetadata());
            if (metadata.path("schemaVersion").asInt() != 2) throw input();
            NdviMetadataValidator.validate(metadata, task.getTaskType());
            NdviMetadataValidator.validateTaskRegion(metadata, task.getParams());
            JsonNode region = metadata.path("regionSnapshot");
            require(region.path("version").isIntegralNumber() && region.path("version").canConvertToInt()
                    && region.path("version").intValue() > 0);
            ObjectNode identity = object().put("fileId", file.getId()).put("bucket", file.getMinioBucket())
                    .put("objectKey", file.getObjectKey()).put("checksum", file.getChecksum()).put("size", file.getFileSize());
            identity.set("metadata", metadata);
            return new Snapshot(hash(identity), metadata);
        } catch (BusinessException | IllegalStateException error) {
            throw new AgentFailure("STATISTICS_UNAVAILABLE", "没有可解读的区域统计", 409, false);
        }
    }

    private ObjectNode replay(TaskResultAnswerStore.Turn turn, String version, String question) {
        if (turn == null) throw AgentFailure.conflict("REQUEST_UNAVAILABLE");
        if (!turn.version().equals(version) || !turn.question().equals(question))
            throw AgentFailure.conflict("REQUEST_MISMATCH");
        return view(turn);
    }

    private ObjectNode view(TaskResultAnswerStore.Turn turn) {
        if (turn == null) return null;
        ObjectNode response = object().put("requestId", turn.requestId().toString()).put("resultVersion", turn.version())
                .put("question", turn.question()).put("status", turn.status()).put("error", turn.error());
        return response.set("answer", parse(turn.answer()));
    }

    private ObjectNode answer(JsonNode selection, Snapshot snapshot) {
        fields(selection, "requestId", "kind", "usage", "modelRequested", "modelReturned", "elapsedMs");
        Topic topic;
        try {
            topic = Topic.valueOf(text(selection, "kind", 40));
        } catch (IllegalArgumentException error) {
            throw new AgentFailure("MODEL_OUTPUT_INVALID", "未取得可核对的回答", 503, true);
        }
        ObjectNode answer = object().put("kind", topic.name()).put("statement", topic.statement)
                .put("regionVersion", snapshot.metadata().path("regionSnapshot").path("version").intValue());
        var evidence = answer.putArray("evidence");
        for (Metric metric : topic.metrics) {
            JsonNode value = metric == Metric.COVERAGE ? snapshot.metadata().path(metric.key)
                    : snapshot.metadata().path("statistics").path(metric.key);
            evidence.addObject().put("key", metric.key).put("label", metric.label).put("unit", metric.unit).set("value", value);
        }
        return answer;
    }

    private record Snapshot(String version, JsonNode metadata) {}

    private enum Metric {
        MEAN("mean", "区域 NDVI 均值", "decimal"), MIN("min", "最小值", "decimal"), MAX("max", "最大值", "decimal"),
        VALID("validPixelCount", "有效像素", "count"), TOTAL("totalPixelCount", "区域内总像素", "count"),
        INVALID("invalidPixelCount", "无效像素", "count"), RATIO("validPixelRatio", "有效像素比例", "ratio"),
        COVERAGE("coverageRatio", "影像范围覆盖率", "ratio");

        final String key;
        final String label;
        final String unit;

        Metric(String key, String label, String unit) {
            this.key = key;
            this.label = label;
            this.unit = unit;
        }
    }

    private enum Topic {
        SUMMARY("本次结果的均值与范围来自区域内有效像素。",
                Metric.MEAN, Metric.MIN, Metric.MAX, Metric.VALID, Metric.TOTAL, Metric.RATIO),
        MEAN("区域均值反映参与本次统计的有效像素的 NDVI 平均水平。", Metric.MEAN, Metric.VALID),
        RANGE("最小值和最大值是本次有效像素中观测到的数值范围。", Metric.MIN, Metric.MAX),
        VALID_PIXELS("有效像素比例以区域内总像素为分母。", Metric.VALID, Metric.TOTAL, Metric.INVALID, Metric.RATIO),
        COVERAGE("影像范围覆盖率描述监测区域落在影像范围内的比例。", Metric.COVERAGE),
        NEEDS_SPATIAL_EVIDENCE("现有统计描述全区，需要像素分布或分区统计才能回答这个问题。"),
        INSUFFICIENT_EVIDENCE("当前任务的 NDVI 统计无法支持这个问题。");

        final String statement;
        final List<Metric> metrics;

        Topic(String statement, Metric... metrics) {
            this.statement = statement;
            this.metrics = List.of(metrics);
        }
    }
}
