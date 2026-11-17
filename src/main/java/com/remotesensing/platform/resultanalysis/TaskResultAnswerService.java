package com.remotesensing.platform.resultanalysis;

import static com.remotesensing.platform.agent.AgentJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentFailure;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultMetadataValidator;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

@Service
public class TaskResultAnswerService {
    private static final int HISTORY_PAGE = 30;
    private final CurrentUserContext users;
    private final RsTaskMapper tasks;
    private final RsResultFileMapper results;
    private final TaskResultAnswerStore store;
    private final TaskResultSpatialEvidence spatial;

    TaskResultAnswerService(CurrentUserContext users, RsTaskMapper tasks, RsResultFileMapper results,
                            TaskResultAnswerStore store, TaskResultSpatialEvidence spatial) {
        this.users = users;
        this.tasks = tasks;
        this.results = results;
        this.store = store;
        this.spatial = spatial;
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
        ObjectNode state = object().put("currentVersion", active).put("resultVersion", selected)
                .put("canAsk", false).put("hasEarlier", false);
        var turns = state.putArray("turns");
        var versions = state.putArray("historyVersions");
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
            // An install using the new knowledge endpoint may never have enabled result-answer history.
            String sqlState = error.getSQLException() == null ? null : error.getSQLException().getSQLState();
            if (!("42P01".equals(sqlState) || "42703".equals(sqlState))) throw error;
        }
        return state;
    }

    public ObjectNode evidence(long taskId, String version, String section) {
        require(version.matches("[a-f0-9]{64}") && TaskResultSpatialEvidence.SECTIONS.contains(section));
        String owner = users.getAuthenticatedUserId();
        Snapshot snapshot = snapshot(owner, taskId);
        if (!version.equals(snapshot.version())) throw AgentFailure.conflict("RESULT_CHANGED");
        JsonNode geometry = spatial.geometry(owner, taskId, version, section, snapshot.file());
        if (!snapshot(owner, taskId).version().equals(version)) throw AgentFailure.conflict("RESULT_CHANGED");
        return object().put("fileId", snapshot.file().getId().toString())
                .put("version", version).put("section", section).set("geometry", geometry);
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
        String algorithm = task.getTaskType();
        if (!"SUCCESS".equals(task.getStatus()) || !("NDVI".equals(algorithm) || "NDWI".equals(algorithm)
                || "CHANGE_DETECTION".equals(algorithm)))
            throw new AgentFailure("RESULT_UNAVAILABLE", "任务计算完成后可解读", 409, false);

        RsResultFile file = results.selectByTaskId(taskId);
        if (file == null || !owner.equals(file.getOwnerId()) || !Objects.equals(file.getTaskId(), taskId)
                || !Objects.equals(file.getImageId(), task.getImageId()) || file.getId() == null)
            throw new AgentFailure("RESULT_UNAVAILABLE", "没有可核对的任务结果", 409, false);
        try {
            JsonNode metadata = parse(file.getResultMetadata());
            require(metadata.isObject());
            ResultMetadataValidator.validate(metadata, algorithm, task.getParams());
            if ("NDVI".equals(algorithm)) {
                JsonNode region = metadata.path("regionSnapshot");
                require(region.path("version").isIntegralNumber() && region.path("version").canConvertToInt()
                        && region.path("version").intValue() > 0);
            }
            ObjectNode identity = object().put("fileId", file.getId()).put("bucket", file.getMinioBucket())
                    .put("objectKey", file.getObjectKey()).put("checksum", file.getChecksum()).put("size", file.getFileSize());
            identity.set("metadata", metadata);
            JsonNode sources = "CHANGE_DETECTION".equals(algorithm) ? parse(task.getParams()).path("changeSourceSnapshot") : null;
            if (sources != null) identity.set("sources", sources);
            return new Snapshot(hash(identity), file);
        } catch (BusinessException | IllegalStateException error) {
            String message = switch (algorithm) {
                case "NDWI" -> "这份 NDWI 结果缺少可核对的有效像素统计，仍可查看原图层";
                case "CHANGE_DETECTION" -> "这份结果缺少可核对的两期对齐信息或共同有效像素分母，仍可查看原图层";
                default -> "没有可解读的区域统计";
            };
            throw new AgentFailure("STATISTICS_UNAVAILABLE", message, 409, false);
        }
    }

    private ObjectNode view(TaskResultAnswerStore.Turn turn) {
        ObjectNode response = object().put("requestId", turn.requestId().toString()).put("resultVersion", turn.version())
                .put("number", turn.number()).put("question", turn.question()).put("status", turn.status()).put("error", turn.error());
        return response.set("answer", parse(turn.answer()));
    }

    private record Snapshot(String version, RsResultFile file) {}
}
