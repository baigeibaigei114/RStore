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
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

@Service
public class TaskResultAnswerService {
    private static final int HISTORY_PAGE = 30;
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

    public ObjectNode get(long taskId, String version, Integer beforeTurn) {
        require(version == null || version.matches("[a-f0-9]{64}"));
        require(beforeTurn == null || beforeTurn > 0);
        String owner = users.getAuthenticatedUserId();
        RsTask task = ownedTask(owner, taskId);
        String active = null;
        String message = null;
        try {
            active = snapshot(task, owner, taskId).version();
        } catch (AgentFailure error) {
            message = error.getMessage();
        }
        String selected = version == null ? active : version;
        boolean enabled = limits.isEnabled();
        ObjectNode state = object().put("currentVersion", active).put("resultVersion", selected)
                .put("canAsk", enabled && active != null && active.equals(selected)).put("hasEarlier", false);
        var turns = state.putArray("turns");
        var versions = state.putArray("historyVersions");
        if (!enabled) message = "结果解读服务暂未启用";
        if (message != null) state.put("message", message);
        try {
            List<String> known = store.versions(owner, taskId);
            if (selected != null) {
                List<TaskResultAnswerStore.Turn> page = store.history(owner, taskId, selected,
                        beforeTurn == null ? Integer.MAX_VALUE : beforeTurn, HISTORY_PAGE + 1);
                if (!selected.equals(active) && page.isEmpty() && !known.contains(selected))
                    throw new AgentFailure("HISTORY_NOT_FOUND", "没有可查看的历史解读", 404, false);
                state.put("hasEarlier", page.size() > HISTORY_PAGE);
                for (int index = Math.min(page.size(), HISTORY_PAGE) - 1; index >= 0; index--) {
                    turns.add(view(page.get(index)));
                }
            }
            for (String old : known) {
                if (!old.equals(active)) versions.add(old);
            }
        } catch (BadSqlGrammarException error) {
            // Agent-disabled installs may not have opted into either result-answer migration.
            String sqlState = error.getSQLException() == null ? null : error.getSQLException().getSQLState();
            if (enabled || !("42P01".equals(sqlState) || "42703".equals(sqlState))) throw error;
        }
        return state;
    }

    public ObjectNode ask(long taskId, JsonNode body) {
        fields(body, "requestId", "resultVersion", "question", "expectedTurn");
        UUID requestId = UUID.fromString(uuid(body, "requestId"));
        String version = hashField(body, "resultVersion");
        int expectedTurn = integer(body, "expectedTurn", 0);
        require(expectedTurn < Integer.MAX_VALUE - 1);
        String question = text(body, "question", 500).strip();
        sensitive(question);
        String owner = users.getAuthenticatedUserId();
        Snapshot snapshot = snapshot(owner, taskId);
        if (!snapshot.version().equals(version)) throw AgentFailure.conflict("RESULT_CHANGED");
        if (!limits.isEnabled()) throw new AgentFailure("MODEL_UNAVAILABLE", "结果解读服务暂未启用", 503, true);

        TaskResultAnswerStore.Turn existing = store.find(owner, taskId, requestId);
        if (existing != null) return replay(existing, version, question, expectedTurn);
        if (!store.reserve(owner, taskId, requestId, version, question, limits.getOperationSeconds(), expectedTurn)) {
            return replay(store.find(owner, taskId, requestId), version, question, expectedTurn);
        }

        // The durable reservation owns the only model call. There is no DB transaction around HTTP.
        try {
            rate.check("agent:" + owner, limits.getOwnerRateLimit(), Duration.ofSeconds(limits.getOwnerRateSeconds()));
            Topic previous = previousTopic(owner, taskId, version, expectedTurn);
            ObjectNode prompt = object().put("question", question);
            if (previous != null) prompt.put("previousKind", previous.name());
            JsonNode selection = agent.post("result-answers", prompt,
                    Duration.ofSeconds(limits.getRoundSeconds()));
            ObjectNode answer = answer(selection, snapshot, previous);
            if (!snapshot(owner, taskId).version().equals(version)) throw AgentFailure.conflict("RESULT_CHANGED");
            store.complete(owner, taskId, requestId, answer.toString());
        } catch (BusinessException error) {
            String message = error.getCode() == 409 ? "任务结果已变化，请刷新后重新提问"
                    : error.getCode() == 429 ? "提问过于频繁，请稍后重试" : "本次解读未完成，可以重新提问";
            store.fail(owner, taskId, requestId, message);
            if (error.getCode() == 404 || error.getCode() == 409) throw error;
        }
        return replay(store.find(owner, taskId, requestId), version, question, expectedTurn);
    }

    private Topic previousTopic(String owner, long taskId, String version, int expectedTurn) {
        if (expectedTurn == 0) return null;
        TaskResultAnswerStore.Turn prior = store.previousComplete(owner, taskId, version, expectedTurn);
        if (prior == null) return null;
        Topic topic = Topic.valueOf(parse(prior.answer()).path("kind").asText());
        return topic.metrics.isEmpty() ? null : topic;
    }

    private RsTask ownedTask(String owner, long taskId) {
        RsTask task = tasks.selectByIdForOwner(taskId, owner);
        if (task == null || !owner.equals(task.getOwnerId()))
            throw new AgentFailure("TASK_NOT_FOUND", "任务不存在或不可访问", 404, false);
        return task;
    }

    private Snapshot snapshot(String owner, long taskId) {
        return snapshot(ownedTask(owner, taskId), owner, taskId);
    }

    private Snapshot snapshot(RsTask task, String owner, long taskId) {
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

    private ObjectNode replay(TaskResultAnswerStore.Turn turn, String version, String question, int expectedTurn) {
        if (turn == null) throw AgentFailure.conflict("TURN_CHANGED");
        if (!turn.version().equals(version) || !turn.question().equals(question) || turn.number() != expectedTurn + 1)
            throw AgentFailure.conflict("REQUEST_MISMATCH");
        return view(turn);
    }

    private ObjectNode view(TaskResultAnswerStore.Turn turn) {
        ObjectNode response = object().put("requestId", turn.requestId().toString()).put("resultVersion", turn.version())
                .put("number", turn.number()).put("question", turn.question()).put("status", turn.status()).put("error", turn.error());
        return response.set("answer", parse(turn.answer()));
    }

    private ObjectNode answer(JsonNode selection, Snapshot snapshot, Topic previous) {
        fields(selection, "requestId", "kind", "usage", "modelRequested", "modelReturned", "elapsedMs");
        Topic topic;
        try {
            String kind = text(selection, "kind", 40);
            if ("FOLLOW_UP".equals(kind)) {
                topic = previous == Topic.MEAN || previous == Topic.VALID_RATIO || previous == Topic.COVERAGE
                        ? previous : Topic.CLARIFY;
            } else {
                topic = Topic.valueOf(kind);
            }
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
        VALID_RATIO("有效像素比例以区域内总像素为分母。", Metric.VALID, Metric.TOTAL, Metric.RATIO),
        COVERAGE("影像范围覆盖率描述监测区域落在影像范围内的比例。", Metric.COVERAGE),
        CLARIFY("请说明你指的是哪一项统计，例如均值、有效像素比例或数值范围。"),
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
