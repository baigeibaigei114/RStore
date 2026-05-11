package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RsTaskFailureServiceImpl implements RsTaskFailureService {

    private final RsTaskMapper taskMapper;
    private final RsImageMapper imageMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final ObjectMapper objectMapper;

    public RsTaskFailureServiceImpl(RsTaskMapper taskMapper,
                                    RsImageMapper imageMapper,
                                    RsTaskLogMapper taskLogMapper,
                                    ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.imageMapper = imageMapper;
        this.taskLogMapper = taskLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String errorMessage, Object detail) {
        if (taskId == null) {
            return;
        }

        RsTask task = taskMapper.selectById(taskId);
        // 状态和日志放在同一个事务中，保证前端状态与后台排障记录保持一致。
        taskMapper.markFailed(taskId, errorMessage);
        if (task != null && taskMapper.countUnfinishedByImageId(task.getImageId()) == 0) {
            imageMapper.markReadyIfProcessing(task.getImageId());
        }

        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel("ERROR");
        taskLog.setMessage(errorMessage);
        taskLog.setDetail(toJson(detail));
        taskLogMapper.insert(taskLog);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"error\":\"task failure detail serialization failed\"}";
        }
    }
}
