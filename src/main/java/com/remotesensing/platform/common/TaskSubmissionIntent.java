package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.exception.BusinessException;
import java.io.IOException;
import java.util.List;

/** 原始提交意图与执行快照分离，避免默认参数填充和区域编辑影响重试。 */
public final class TaskSubmissionIntent {
    public static final String FIELD = "_submissionIntent";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SERVER_FIELDS = List.of(
            FIELD, "inputSnapshot", "regionSnapshot",
            "beforeObjectKey", "afterObjectKey", "beforeBucket", "afterBucket", "afterImageId");

    private TaskSubmissionIntent() {}

    public static ObjectNode from(RsTaskSubmitDTO dto) {
        ObjectNode intent = JSON.createObjectNode();
        intent.put("version", 1);
        intent.set("imageId", JSON.valueToTree(dto.getImageId()));
        intent.set("taskType", JSON.valueToTree(dto.getTaskType()));
        intent.set("monitoringRegionId", JSON.valueToTree(dto.getMonitoringRegionId()));
        ObjectNode params = dto.getParams() == null
                ? JSON.createObjectNode() : JSON.valueToTree(dto.getParams());
        params.remove(SERVER_FIELDS);
        intent.set("params", params);
        try {
            // 与 JSONB 读回的数字节点保持一致（小整数可能由 LongNode 变为 IntNode）。
            return (ObjectNode) JSON.readTree(intent.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot normalize submission intent", exception);
        }
    }

    public static void requireMatch(String storedParams, JsonNode intent) {
        try {
            JsonNode params = storedParams == null ? null : JSON.readTree(storedParams);
            JsonNode previous = params == null ? null : params.get(FIELD);
            if (previous == null) {
                throw new BusinessException(ResultCode.CONFLICT.getCode(), "历史幂等键缺少请求意图，请使用新的 clientRequestId");
            }
            if (!previous.equals(intent)) {
                throw new BusinessException(ResultCode.CONFLICT.getCode(), "clientRequestId 已用于不同的请求参数");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Stored task params are invalid", exception);
        }
    }
}
