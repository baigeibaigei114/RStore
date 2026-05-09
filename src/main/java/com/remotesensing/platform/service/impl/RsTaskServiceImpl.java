package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import com.remotesensing.platform.service.RsTaskService;
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
    private static final String STATUS_PENDING = "PENDING";

    private final RsImageMapper imageMapper;
    private final RsTaskMapper taskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitTaskProperties rabbitTaskProperties;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;
    private final RsTaskFailureService taskFailureService;
    private final TransactionTemplate transactionTemplate;

    public RsTaskServiceImpl(RsImageMapper imageMapper,
                             RsTaskMapper taskMapper,
                             RabbitTemplate rabbitTemplate,
                             RabbitTaskProperties rabbitTaskProperties,
                             MinioProperties minioProperties,
                             ObjectMapper objectMapper,
                             RsTaskFailureService taskFailureService,
                             PlatformTransactionManager transactionManager) {
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
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
            throw new BusinessException(ResultCode.FAIL.getCode(), "任务提交失败，事务未返回任务信息");
        }

        sendTaskMessage(preparedTask, submitDTO);
        return new RsTaskSubmitVO(preparedTask.task().getId());
    }

    private PreparedTask prepareTask(RsTaskSubmitDTO submitDTO) {
        RsImage image = imageMapper.selectById(submitDTO.getImageId());
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录不存在");
        }

        // 任务和输出路径在同一个事务内落库，避免出现缺少结果路径的 PENDING 任务。
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
            taskFailureService.markFailed(
                    task.getId(),
                    "RabbitMQ 消息发送异常：" + exception.getMessage(),
                    buildPublishExceptionDetail(exception)
            );
            throw new BusinessException(ResultCode.FAIL.getCode(), "任务提交失败，RabbitMQ 消息发送异常");
        }
    }

    private RsTask buildPendingTask(RsTaskSubmitDTO submitDTO) {
        RsTask task = new RsTask();
        task.setTaskCode("TASK_" + UUID.randomUUID().toString().replace("-", ""));
        task.setImageId(submitDTO.getImageId());
        task.setTaskType(submitDTO.getTaskType().name());
        task.setTaskName(submitDTO.getTaskType().name() + " 处理任务");
        task.setStatus(STATUS_PENDING);
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
        // RabbitMQ 消息只传 MinIO 路径和计算参数，避免大文件进入消息队列。
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
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务参数 JSON 序列化失败");
        }
    }

    private record PreparedTask(RsTask task, RsImage image, String outputObjectKey) {
    }
}
