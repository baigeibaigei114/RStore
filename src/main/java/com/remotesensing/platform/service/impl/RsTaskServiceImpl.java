package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.enums.ImageStatus;
import com.remotesensing.platform.common.enums.TaskClaimAction;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RsTaskServiceImpl implements RsTaskService {

    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");

    private final RsImageMapper imageMapper;
    private final RsTaskMapper taskMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitTaskProperties rabbitTaskProperties;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;
    private final RsTaskFailureService taskFailureService;
    private final TransactionTemplate transactionTemplate;

    public RsTaskServiceImpl(RsImageMapper imageMapper,
                             RsTaskMapper taskMapper,
                             RsTaskLogMapper taskLogMapper,
                             RabbitTemplate rabbitTemplate,
                             RabbitTaskProperties rabbitTaskProperties,
                             MinioProperties minioProperties,
                             ObjectMapper objectMapper,
                             RsTaskFailureService taskFailureService,
                             PlatformTransactionManager transactionManager) {
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTaskProperties = rabbitTaskProperties;
        this.minioProperties = minioProperties;
        this.objectMapper = objectMapper;
        this.taskFailureService = taskFailureService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO) {
        PreparedTask preparedTask = transactionTemplate.execute(status -> prepareTask(submitDTO));
        if (preparedTask == null) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "Task submit failed: transaction returned no task");
        }

        sendTaskMessage(preparedTask, submitDTO);
        return new RsTaskSubmitVO(preparedTask.task().getId());
    }

    @Override
    public void updateStatus(Long taskId, RsTaskStatusUpdateDTO updateDTO) {
        transactionTemplate.executeWithoutResult(status -> updateStatusInTransaction(taskId, updateDTO));
    }

    @Override
    public RsTaskClaimVO claim(Long taskId) {
        return transactionTemplate.execute(status -> claimInTransaction(taskId));
    }

    private RsTaskClaimVO claimInTransaction(Long taskId) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Task does not exist");
        }

        TaskStatus currentStatus = TaskStatus.fromDb(task.getStatus());
        if (currentStatus.isTerminal()) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_FINISHED.dbValue(), task.getStatus(), "Task is terminal; skip duplicate work", task.getOutputObjectKey());
        }
        if (currentStatus == TaskStatus.RUNNING) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_RUNNING.dbValue(), task.getStatus(), "Task is already running", task.getOutputObjectKey());
        }

        int updated = taskMapper.claimForRunning(taskId);
        if (updated <= 0) {
            RsTask latestTask = taskMapper.selectById(taskId);
            String latestStatus = latestTask == null ? task.getStatus() : latestTask.getStatus();
            return new RsTaskClaimVO(false, TaskClaimAction.CLAIM_REJECTED.dbValue(), latestStatus, "Task status changed; claim rejected", task.getOutputObjectKey());
        }

        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel("INFO");
        taskLog.setMessage("Worker claimed task: " + task.getStatus() + " -> " + TaskStatus.RUNNING.dbValue());
        taskLog.setDetail(toJson(Map.of("fromStatus", task.getStatus(), "toStatus", TaskStatus.RUNNING.dbValue())));
        taskLogMapper.insert(taskLog);
        return new RsTaskClaimVO(true, TaskClaimAction.CLAIMED.dbValue(), TaskStatus.RUNNING.dbValue(), "Task claimed", task.getOutputObjectKey());
    }

    private void updateStatusInTransaction(Long taskId, RsTaskStatusUpdateDTO updateDTO) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Task does not exist");
        }

        TaskStatus targetStatus = normalizeStatus(updateDTO.getStatus());
        validateTransition(TaskStatus.fromDb(task.getStatus()), targetStatus);
        String errorMessage = resolveErrorMessage(targetStatus, updateDTO);
        if (targetStatus == TaskStatus.SUCCESS && isBlank(updateDTO.getOutputObjectKey()) && isBlank(task.getOutputObjectKey())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "SUCCESS status requires outputObjectKey");
        }
        if (targetStatus == TaskStatus.FAILED && isBlank(errorMessage)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "FAILED status requires errorMessage");
        }

        int updated = taskMapper.updateStatusFromWorker(
                taskId,
                task.getStatus(),
                targetStatus.dbValue(),
                updateDTO.getProgress(),
                updateDTO.getOutputObjectKey(),
                errorMessage
        );
        if (updated <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Task status changed concurrently; please retry callback");
        }
        insertStatusLog(task, targetStatus.dbValue(), updateDTO, errorMessage);
        releaseImageIfTaskFinished(task, targetStatus);
    }

    private PreparedTask prepareTask(RsTaskSubmitDTO submitDTO) {
        RsImage image = imageMapper.selectById(submitDTO.getImageId());
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Image does not exist");
        }
        if (!ImageStatus.fromDb(image.getStatus()).canSubmitTask()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Only READY images can submit processing tasks");
        }

        // The conditional update closes the submit/delete concurrency window.
        if (imageMapper.markProcessingIfReady(image.getId()) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Image status does not allow task submission");
        }

        // Persist task and output path in one short transaction to avoid incomplete PENDING tasks.
        RsTask task = buildPendingTask(submitDTO);
        taskMapper.insert(task);

        String outputObjectKey = buildOutputObjectKey(submitDTO.getTaskType().name(), task.getId());
        taskMapper.updateOutputObject(task.getId(), minioProperties.getBucketName(), outputObjectKey);
        return new PreparedTask(task, image, outputObjectKey);
    }

    private void sendTaskMessage(PreparedTask preparedTask, RsTaskSubmitDTO submitDTO) {
        RsTask task = preparedTask.task();
        RemoteSensingTaskMessage message = buildMessage(submitDTO, preparedTask.image(), task.getId(), preparedTask.outputObjectKey());
        try {
            rabbitTemplate.convertAndSend(
                    rabbitTaskProperties.getExchange(),
                    rabbitTaskProperties.getRoutingKey(),
                    message,
                    rabbitMessage -> {
                        rabbitMessage.getMessageProperties().setHeader("taskId", task.getId());
                        rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return rabbitMessage;
                    },
                    new CorrelationData(String.valueOf(task.getId()))
            );
        } catch (AmqpException exception) {
            taskFailureService.markFailedIfActive(
                    task.getId(),
                    "RabbitMQ send exception: " + exception.getMessage(),
                    buildPublishExceptionDetail(exception)
            );
            throw new BusinessException(ResultCode.FAIL.getCode(), "Task submit failed: RabbitMQ send exception");
        }
    }

    private RsTask buildPendingTask(RsTaskSubmitDTO submitDTO) {
        RsTask task = new RsTask();
        task.setTaskCode("TASK_" + UUID.randomUUID().toString().replace("-", ""));
        task.setImageId(submitDTO.getImageId());
        task.setTaskType(submitDTO.getTaskType().name());
        task.setTaskName(submitDTO.getTaskType().name() + " processing task");
        task.setStatus(TaskStatus.PENDING.dbValue());
        task.setPriority(5);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setMaxRetryCount(rabbitTaskProperties.getMaxRetryCount());
        task.setParams(toJson(submitDTO.getParams()));
        return task;
    }

    private RemoteSensingTaskMessage buildMessage(RsTaskSubmitDTO submitDTO,
                                                  RsImage image,
                                                  Long taskId,
                                                  String outputObjectKey) {
        // RabbitMQ carries object paths and parameters only; GeoTIFF bytes stay in MinIO.
        RemoteSensingTaskMessage message = new RemoteSensingTaskMessage();
        message.setTaskId(taskId);
        message.setTaskType(submitDTO.getTaskType());
        message.setInputBucket(image.getMinioBucket());
        message.setInputObjectKey(image.getObjectKey());
        message.setOutputBucket(minioProperties.getBucketName());
        message.setOutputObjectKey(outputObjectKey);
        message.setParams(submitDTO.getParams());
        return message;
    }

    private String buildOutputObjectKey(String taskType, Long taskId) {
        LocalDate now = LocalDate.now();
        return "result/%s/%s/%s/task_%s.tif".formatted(
                taskType,
                YEAR_FORMATTER.format(now),
                MONTH_FORMATTER.format(now),
                taskId
        );
    }

    private Map<String, Object> buildPublishExceptionDetail(AmqpException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exchange", rabbitTaskProperties.getExchange());
        detail.put("routingKey", rabbitTaskProperties.getRoutingKey());
        detail.put("exceptionType", exception.getClass().getName());
        detail.put("message", exception.getMessage());
        return detail;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Task params JSON serialization failed");
        }
    }

    private TaskStatus normalizeStatus(String status) {
        try {
            return TaskStatus.fromDb(status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Invalid task status: " + status);
        }
    }

    private void validateTransition(TaskStatus currentStatus, TaskStatus targetStatus) {
        if (!currentStatus.canTransitTo(targetStatus)) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR.getCode(),
                    "Invalid task status transition: " + currentStatus + " -> " + targetStatus
            );
        }
        if (currentStatus.isTerminal() && currentStatus != targetStatus) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Terminal task cannot change status again");
        }
    }

    private String resolveErrorMessage(TaskStatus targetStatus, RsTaskStatusUpdateDTO updateDTO) {
        if (targetStatus != TaskStatus.FAILED) {
            return updateDTO.getErrorMessage();
        }
        if (!isBlank(updateDTO.getErrorMessage())) {
            return updateDTO.getErrorMessage();
        }
        return updateDTO.getMessage();
    }

    private void insertStatusLog(RsTask task,
                                 String targetStatus,
                                 RsTaskStatusUpdateDTO updateDTO,
                                 String errorMessage) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", task.getStatus());
        detail.put("toStatus", targetStatus);
        detail.put("progress", updateDTO.getProgress());
        detail.put("outputObjectKey", updateDTO.getOutputObjectKey());
        detail.put("errorMessage", errorMessage);

        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(task.getId());
        taskLog.setLogLevel(TaskStatus.FAILED.dbValue().equals(targetStatus) ? "ERROR" : "INFO");
        taskLog.setMessage(buildStatusLogMessage(task.getStatus(), targetStatus, updateDTO));
        taskLog.setDetail(toJson(detail));
        taskLogMapper.insert(taskLog);
    }

    private void releaseImageIfTaskFinished(RsTask task, TaskStatus targetStatus) {
        if (!targetStatus.isTerminal()) {
            return;
        }
        // Release the image only when no active task remains for the same source asset.
        if (taskMapper.countActiveByImageId(task.getImageId()) == 0) {
            imageMapper.markReadyIfProcessing(task.getImageId());
        }
    }

    private String buildStatusLogMessage(String currentStatus, String targetStatus, RsTaskStatusUpdateDTO updateDTO) {
        if (!isBlank(updateDTO.getMessage())) {
            return updateDTO.getMessage();
        }
        if (currentStatus.equals(targetStatus)) {
            return "Task status callback: " + targetStatus;
        }
        return "Task status changed: " + currentStatus + " -> " + targetStatus;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PreparedTask(RsTask task, RsImage image, String outputObjectKey) {
    }
}
