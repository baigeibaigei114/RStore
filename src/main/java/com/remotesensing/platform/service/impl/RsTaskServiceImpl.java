package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.enums.ImageStatus;
import com.remotesensing.platform.common.enums.ResultFileStatus;
import com.remotesensing.platform.common.enums.TaskClaimAction;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.service.MessageOutboxService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsResultFileVO;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskLogVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RsTaskServiceImpl implements RsTaskService {

    private static final Logger log = LoggerFactory.getLogger(RsTaskServiceImpl.class);
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RsImageMapper imageMapper;
    private final RsTaskMapper taskMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final RsResultFileMapper resultFileMapper;
    private final RabbitTaskProperties rabbitTaskProperties;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;
    private final MessageOutboxService messageOutboxService;
    private final GeoServerService geoServerService;
    private final MinioService minioService;
    private final TransactionTemplate transactionTemplate;
    private final CurrentUserContext currentUserContext;

    public RsTaskServiceImpl(RsImageMapper imageMapper,
                             RsTaskMapper taskMapper,
                             RsTaskLogMapper taskLogMapper,
                             RsResultFileMapper resultFileMapper,
                             RabbitTaskProperties rabbitTaskProperties,
                             MinioProperties minioProperties,
                             ObjectMapper objectMapper,
                             MessageOutboxService messageOutboxService,
                             GeoServerService geoServerService,
                             MinioService minioService,
                             PlatformTransactionManager transactionManager,
                             CurrentUserContext currentUserContext) {
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.resultFileMapper = resultFileMapper;
        this.rabbitTaskProperties = rabbitTaskProperties;
        this.minioProperties = minioProperties;
        this.objectMapper = objectMapper;
        this.messageOutboxService = messageOutboxService;
        this.geoServerService = geoServerService;
        this.minioService = minioService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.currentUserContext = currentUserContext;
    }

    @Override
    public RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO) {
        PreparedTask preparedTask = transactionTemplate.execute(status -> prepareTask(submitDTO));
        if (preparedTask == null) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "任务提交失败：事务未返回任务信息");
        }

        publishTaskMessage(preparedTask);
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

    @Override
    public RsTaskVO getById(Long taskId) {
        RsTaskVO task = taskMapper.selectDetailByIdForOwner(taskId, currentUserContext.getCurrentUserId());
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        return task;
    }

    @Override
    public RsResultFileVO getResultFile(Long taskId) {
        RsTask task = taskMapper.selectByIdForOwner(taskId, currentUserContext.getCurrentUserId());
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }

        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile == null) {
            TaskStatus taskStatus = TaskStatus.fromDb(task.getStatus());
            if (!taskStatus.isTerminal() || taskStatus != TaskStatus.SUCCESS) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务尚未成功，暂无结果文件");
            }
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果文件尚未生成，请稍后重试");
        }
        return toResultFileVO(resultFile);
    }

    @Override
    public FilePresignedUrlVO getResultDownloadUrl(Long taskId) {
        RsTask task = taskMapper.selectByIdForOwner(taskId, currentUserContext.getCurrentUserId());
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        if (TaskStatus.fromDb(task.getStatus()) != TaskStatus.SUCCESS) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务尚未成功，暂无结果文件");
        }

        String objectKey = resolveResultObjectKey(task);
        if (isBlank(objectKey)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果文件尚未生成，请稍后重试");
        }
        return minioService.generatePresignedUrl(objectKey);
    }

    @Override
    public PageResult<RsTaskListVO> page(Integer pageNum, Integer pageSize) {
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        String currentUserId = currentUserContext.getCurrentUserId();
        List<RsTaskListVO> records = taskMapper.selectPage(offset, currentPageSize, currentUserId);
        long total = taskMapper.count(currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    public List<RsTaskLogVO> listLogs(Long taskId) {
        if (taskMapper.selectByIdForOwner(taskId, currentUserContext.getCurrentUserId()) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        return taskLogMapper.selectByTaskIdOrderByCreatedAt(taskId);
    }

    private PreparedTask prepareTask(RsTaskSubmitDTO submitDTO) {
        String currentUserId = currentUserContext.getCurrentUserId();
        RsImage image = imageMapper.selectAccessibleById(submitDTO.getImageId(), currentUserId);
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像不存在或无权访问");
        }
        if (!ImageStatus.fromDb(image.getStatus()).canSubmitTask()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只有 READY 状态的影像可以提交处理任务");
        }

        if (imageMapper.markProcessingIfReady(image.getId()) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像当前状态不允许提交处理任务");
        }

        RsTask task = buildPendingTask(submitDTO, currentUserId);
        taskMapper.insert(task);

        String outputObjectKey = buildOutputObjectKey(submitDTO.getTaskType().name(), task.getId());
        taskMapper.updateOutputObject(task.getId(), minioProperties.getBucketName(), outputObjectKey);
        Long outboxId = messageOutboxService.createTaskMessage(
                task.getId(),
                buildMessage(submitDTO, image, task.getId(), outputObjectKey)
        );
        return new PreparedTask(task, outputObjectKey, outboxId);
    }

    private void publishTaskMessage(PreparedTask preparedTask) {
        try {
            messageOutboxService.publishById(preparedTask.outboxId());
        } catch (RuntimeException exception) {
            log.warn("Outbox 立即投递失败，等待定时补偿，taskId={}, outboxId={}, reason={}",
                    preparedTask.task().getId(), preparedTask.outboxId(), exception.getMessage());
        }
    }

    private RsTaskClaimVO claimInTransaction(Long taskId) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }

        TaskStatus currentStatus = TaskStatus.fromDb(task.getStatus());
        if (currentStatus.isTerminal()) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_FINISHED.dbValue(), task.getStatus(), "任务已进入终态，跳过重复处理", task.getOutputObjectKey());
        }
        if (currentStatus == TaskStatus.RUNNING) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_RUNNING.dbValue(), task.getStatus(), "任务正在运行，跳过本次重复投递", task.getOutputObjectKey());
        }

        int updated = taskMapper.claimForRunning(taskId);
        if (updated <= 0) {
            RsTask latestTask = taskMapper.selectById(taskId);
            String latestStatus = latestTask == null ? task.getStatus() : latestTask.getStatus();
            return new RsTaskClaimVO(false, TaskClaimAction.CLAIM_REJECTED.dbValue(), latestStatus, "任务状态已变化，抢占失败", task.getOutputObjectKey());
        }

        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel("INFO");
        taskLog.setMessage("Worker 抢占任务成功：" + task.getStatus() + " -> " + TaskStatus.RUNNING.dbValue());
        taskLog.setDetail(toJson(Map.of("fromStatus", task.getStatus(), "toStatus", TaskStatus.RUNNING.dbValue())));
        taskLogMapper.insert(taskLog);
        return new RsTaskClaimVO(true, TaskClaimAction.CLAIMED.dbValue(), TaskStatus.RUNNING.dbValue(), "任务抢占成功", task.getOutputObjectKey());
    }

    private void updateStatusInTransaction(Long taskId, RsTaskStatusUpdateDTO updateDTO) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }

        TaskStatus targetStatus = normalizeStatus(updateDTO.getStatus());
        validateTransition(TaskStatus.fromDb(task.getStatus()), targetStatus);
        String errorMessage = resolveErrorMessage(targetStatus, updateDTO);
        if (targetStatus == TaskStatus.SUCCESS && isBlank(updateDTO.getOutputObjectKey()) && isBlank(task.getOutputObjectKey())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "SUCCESS 状态必须提供 outputObjectKey");
        }
        if (targetStatus == TaskStatus.FAILED && isBlank(errorMessage)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "FAILED 状态必须提供 errorMessage");
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
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务状态已被并发更新，请重试回调");
        }
        insertStatusLog(task, targetStatus.dbValue(), updateDTO, errorMessage);
        if (targetStatus == TaskStatus.SUCCESS) {
            createOrUpdateResultFile(task, updateDTO);
            registerGeoServerPublishAfterCommit(taskId);
        }
        releaseImageIfTaskFinished(task, targetStatus);
    }

    private RsTask buildPendingTask(RsTaskSubmitDTO submitDTO, String ownerId) {
        RsTask task = new RsTask();
        task.setOwnerId(ownerId);
        task.setTaskCode("TASK_" + UUID.randomUUID().toString().replace("-", ""));
        task.setImageId(submitDTO.getImageId());
        task.setTaskType(submitDTO.getTaskType().name());
        task.setTaskName(submitDTO.getTaskType().name() + " 处理任务");
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
        // RabbitMQ 只传对象路径和处理参数，GeoTIFF 文件本体始终留在 MinIO。
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

    private TaskStatus normalizeStatus(String status) {
        try {
            return TaskStatus.fromDb(status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "非法任务状态：" + status);
        }
    }

    private void validateTransition(TaskStatus currentStatus, TaskStatus targetStatus) {
        if (!currentStatus.canTransitTo(targetStatus)) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR.getCode(),
                    "非法任务状态流转：" + currentStatus + " -> " + targetStatus
            );
        }
        if (currentStatus.isTerminal() && currentStatus != targetStatus) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "终态任务不允许再次变更状态");
        }
    }

    private String resolveErrorMessage(TaskStatus targetStatus, RsTaskStatusUpdateDTO updateDTO) {
        if (targetStatus == TaskStatus.SUCCESS) {
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

    private void createOrUpdateResultFile(RsTask task, RsTaskStatusUpdateDTO updateDTO) {
        String outputObjectKey = isBlank(updateDTO.getOutputObjectKey())
                ? task.getOutputObjectKey()
                : updateDTO.getOutputObjectKey();
        String outputBucket = isBlank(task.getOutputBucket())
                ? minioProperties.getBucketName()
                : task.getOutputBucket();

        RsResultFile resultFile = buildResultFile(task, outputBucket, outputObjectKey);
        RsResultFile existing = resultFileMapper.selectByTaskId(task.getId());
        if (existing == null) {
            resultFileMapper.insert(resultFile);
            return;
        }

        resultFile.setId(existing.getId());
        resultFileMapper.resetPendingPublish(resultFile);
    }

    private RsResultFile buildResultFile(RsTask task, String outputBucket, String outputObjectKey) {
        RsResultFile resultFile = new RsResultFile();
        resultFile.setOwnerId(task.getOwnerId());
        resultFile.setVisibility(Visibility.PRIVATE.dbValue());
        resultFile.setTaskId(task.getId());
        resultFile.setImageId(task.getImageId());
        resultFile.setFileName(extractFilename(outputObjectKey));
        resultFile.setFileType("GEOTIFF");
        resultFile.setMinioBucket(outputBucket);
        resultFile.setObjectKey(outputObjectKey);
        resultFile.setMimeType("image/tiff");
        resultFile.setStatus(ResultFileStatus.PENDING_PUBLISH.dbValue());
        return resultFile;
    }

    private void registerGeoServerPublishAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            geoServerService.publishTaskResultAsync(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                geoServerService.publishTaskResultAsync(taskId);
            }
        });
    }

    private String extractFilename(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "result.tif";
        }
        int index = objectKey.lastIndexOf('/');
        return index >= 0 ? objectKey.substring(index + 1) : objectKey;
    }

    private RsResultFileVO toResultFileVO(RsResultFile resultFile) {
        RsResultFileVO vo = new RsResultFileVO();
        vo.setId(resultFile.getId());
        vo.setOwnerId(resultFile.getOwnerId());
        vo.setVisibility(resultFile.getVisibility());
        vo.setTaskId(resultFile.getTaskId());
        vo.setImageId(resultFile.getImageId());
        vo.setFileName(resultFile.getFileName());
        vo.setFileType(resultFile.getFileType());
        vo.setMinioBucket(resultFile.getMinioBucket());
        vo.setObjectKey(resultFile.getObjectKey());
        vo.setFileSize(resultFile.getFileSize());
        vo.setMimeType(resultFile.getMimeType());
        vo.setChecksum(resultFile.getChecksum());
        vo.setResultMetadata(resultFile.getResultMetadata());
        vo.setStatus(resultFile.getStatus());
        vo.setWorkspace(resultFile.getWorkspace());
        vo.setStoreName(resultFile.getStoreName());
        vo.setLayerName(resultFile.getLayerName());
        vo.setWmsUrl(resultFile.getWmsUrl());
        vo.setWcsUrl(resultFile.getWcsUrl());
        vo.setPublishErrorMessage(resultFile.getPublishErrorMessage());
        vo.setPublishedAt(resultFile.getPublishedAt());
        vo.setCreatedAt(resultFile.getCreatedAt());
        vo.setUpdatedAt(resultFile.getUpdatedAt());
        return vo;
    }

    private String resolveResultObjectKey(RsTask task) {
        RsResultFile resultFile = resultFileMapper.selectByTaskId(task.getId());
        if (resultFile != null && !isBlank(resultFile.getObjectKey())) {
            return resultFile.getObjectKey();
        }
        return task.getOutputObjectKey();
    }

    private void releaseImageIfTaskFinished(RsTask task, TaskStatus targetStatus) {
        if (!targetStatus.isTerminal()) {
            return;
        }
        // 同一影像没有活跃任务后，才释放影像的 PROCESSING 状态。
        if (taskMapper.countActiveByImageId(task.getImageId()) == 0) {
            imageMapper.markReadyIfProcessing(task.getImageId());
        }
    }

    private String buildStatusLogMessage(String currentStatus, String targetStatus, RsTaskStatusUpdateDTO updateDTO) {
        if (!isBlank(updateDTO.getMessage())) {
            return updateDTO.getMessage();
        }
        if (currentStatus.equals(targetStatus)) {
            return "任务状态回调：" + targetStatus;
        }
        return "任务状态变化：" + currentStatus + " -> " + targetStatus;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private record PreparedTask(RsTask task, String outputObjectKey, Long outboxId) {
    }
}
