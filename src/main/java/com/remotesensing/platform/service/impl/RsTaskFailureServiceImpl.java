package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RsTaskFailureServiceImpl implements RsTaskFailureService {

    private final RsTaskMapper taskMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final ObjectMapper objectMapper;

    public RsTaskFailureServiceImpl(RsTaskMapper taskMapper,
                                    RsTaskLogMapper taskLogMapper,
                                    ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String errorMessage, Object detail) {
        if (taskId == null) {
            return;
        }

        taskMapper.markFailed(taskId, errorMessage);

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
