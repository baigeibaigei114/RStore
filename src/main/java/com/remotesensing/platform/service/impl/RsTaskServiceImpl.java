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
import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.config.properties.RabbitTaskProperties;
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
import com.remotesensing.platform.service.ImageBandCapabilityService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 遥感处理任务服务实现类。
 *
 * <p>职责：管理遥感影像处理任务的完整生命周期，包括任务提交、状态更新、Worker 抢占、
 * 结果文件管理和 GeoServer 图层发布触发等核心操作。
 *
 * <p>核心设计点：
 * <ul>
 *   <li><b>事务边界精确划分</b>：数据准备与消息投递分离 -- {@link #prepareTask} 在事务内完成
 *   影像状态变更、任务入库和 Outbox 消息创建；{@link #publishTaskMessage} 在事务外执行消息投递，
 *   避免消息投递失败回滚数据库操作；</li>
 *   <li><b>Outbox 模式</b>：任务消息先写数据库，再异步投递到 RabbitMQ，投递失败由定时调度补偿，
 *   保证消息不丢失的同时避免分布式事务；</li>
 *   <li><b>乐观锁</b>：{@link #claimInTransaction} 使用 "CAS 式" UPDATE（taskMapper.claimForRunning），
 *   保证同一任务不会被多个 Worker 重复抢占；</li>
 *   <li><b>状态机防护</b>：{@link #validateTransition} 校验状态流转合法性，终态任务不可回退；</li>
 *   <li><b>afterCommit 回调</b>：{@link #registerGeoServerPublishAfterCommit} 将 GeoServer 发布
 *   延后到事务提交后执行，避免长事务阻塞数据库连接池；</li>
 *   <li><b>影像状态联动</b>：{@link #releaseImageIfTaskFinished} 在任务进入终态后，
 *   检查同一影像是否还有其他活跃任务，无活跃任务才释放影像的 PROCESSING 状态。</li>
 * </ul>
 */
@Service
public class RsTaskServiceImpl implements RsTaskService {

    private static final Logger log = LoggerFactory.getLogger(RsTaskServiceImpl.class);
    /** 日期格式化器（年），用于构建结果文件路径中的 yyyy 层级。 */
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    /** 日期格式化器（月），用于构建结果文件路径中的 MM 层级。 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    /** 分页默认页码。 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 分页默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 分页每页最大条数，防止客户端请求超大分页导致数据库压力过大。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final RsImageMapper imageMapper;
    private final RsTaskMapper taskMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final RsResultFileMapper resultFileMapper;
    private final RabbitTaskProperties rabbitTaskProperties;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;
    private final ImageBandCapabilityService imageBandCapabilityService;
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
                             ImageBandCapabilityService imageBandCapabilityService,
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
        this.imageBandCapabilityService = imageBandCapabilityService;
        this.messageOutboxService = messageOutboxService;
        this.geoServerService = geoServerService;
        this.minioService = minioService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.currentUserContext = currentUserContext;
    }

    /**
     * 提交遥感影像处理任务。
     *
     * <p>事务边界设计：任务提交拆分为两个步骤 --
     * <ol>
     *   <li>{@link #prepareTask} 在事务内完成：影像状态变更、任务记录插入、Outbox 消息创建；</li>
     *   <li>{@link #publishTaskMessage} 在事务外执行 RabbitMQ 消息投递。</li>
     * </ol>
     * 这样划分的原因是：消息投递可能因网络、队列满等原因失败，若放在事务内会导致数据库操作回滚，
     * 浪费已完成的准备工作。事务外投递失败后，定时补偿调度器会重新投递。
     *
     * @param submitDTO 任务提交参数（影像 ID、任务类型、处理参数等）
     * @return 提交结果（任务 ID）
     * @throws BusinessException 影像不可提交 / 无权访问 / 事务异常时抛出
     */
    @Override
    public RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO) {
        // 第一阶段：事务内完成所有数据变更。
        PreparedTask preparedTask;
        try {
            preparedTask = transactionTemplate.execute(status -> prepareTask(submitDTO));
        } catch (DuplicateKeyException exception) {
            return resolveDuplicateSubmit(submitDTO, exception);
        }
        if (preparedTask == null) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "任务提交失败：事务未返回任务信息");
        }

        // 第二阶段：事务外执行消息投递，投递失败不影响数据库状态。
        if (preparedTask.publishRequired()) {
            publishTaskMessage(preparedTask);
        }
        return new RsTaskSubmitVO(preparedTask.task().getId());
    }

    /**
     * 更新任务状态（Worker 回调接口）。
     *
     * <p>事务边界：整个状态更新在事务内完成。关键步骤包括：
     * <ul>
     *   <li>乐观锁更新：使用 {@code taskMapper.updateStatusFromWorker} 的 WHERE status=oldStatus
     *   条件进行 CAS 式更新，防止并发回调覆盖状态；</li>
     *   <li>终态联动：任务进入 SUCCESS 时创建结果文件和注册 GeoServer 发布回调；
     *   任务进入终态时释放影像的 PROCESSING 锁；</li>
     *   <li>严格约束：SUCCESS 必须提供 outputObjectKey，FAILED 必须提供 errorMessage。</li>
     * </ul>
     *
     * @param taskId   任务 ID
     * @param updateDTO 状态更新参数（目标状态、进度、对象键、错误信息等）
     * @throws BusinessException 任务不存在 / 状态流转非法 / CAS 更新失败 / 参数缺失时抛出
     */
    @Override
    public void updateStatus(Long taskId, RsTaskStatusUpdateDTO updateDTO) {
        transactionTemplate.executeWithoutResult(status -> updateStatusInTransaction(taskId, updateDTO));
    }

    /**
     * Worker 抢占任务。
     *
     * <p>使用乐观锁（CAS UPDATE）保证同一任务不会被多个 Worker 同时抢占。
     * 如果任务已处于终态或 RUNNING 状态，直接返回当前状态信息，不抛出异常。
     * 抢占成功后记录任务日志。
     *
     * @param taskId 任务 ID
     * @return 抢占结果（是否抢占成功、动作说明、当前状态、对象键等）
     */
    @Override
    public RsTaskClaimVO claim(Long taskId) {
        return transactionTemplate.execute(status -> claimInTransaction(taskId));
    }

    /**
     * 根据 ID 查询任务详情（仅返回当前用户有权限访问的任务）。
     *
     * <p>事务属性：只读事务。
     *
     * @param taskId 任务 ID
     * @return 任务详情视图
     * @throws BusinessException 任务不存在时抛出
     */
    @Override
    public RsTaskVO getById(Long taskId) {
        RsTaskVO task = taskMapper.selectDetailByIdForOwner(taskId, currentUserContext.getCurrentUserId());
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        return task;
    }

    /**
     * 获取任务结果文件。
     *
     * <p>如果结果文件记录不存在，根据任务状态区分为：
     * 非 SUCCESS 终态 -> "任务尚未成功"；SUCCESS 终态 -> "结果文件尚未生成，请重试"。
     *
     * @param taskId 任务 ID
     * @return 结果文件视图
     * @throws BusinessException 任务不存在或结果不可用时抛出
     */
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

    /**
     * 获取任务结果文件的预签名下载 URL。
     *
     * <p>优先从结果文件表获取 objectKey，如果结果文件表尚未写入，则回退到任务的 outputObjectKey。
     * 仅 SUCCESS 状态的任务允许下载。
     *
     * @param taskId 任务 ID
     * @return 预签名下载 URL 信息
     * @throws BusinessException 任务不存在 / 非 SUCCESS 状态 / 结果不可用时抛出
     */
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

    /**
     * 分页查询当前用户的任务列表。
     *
     * @param pageNum  页码（从 1 开始，为空时默认 1）
     * @param pageSize 每页条数（为空时默认 10，最大 100）
     * @return 分页结果
     */
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

    /**
     * 查询任务的操作日志列表。
     *
     * @param taskId 任务 ID
     * @return 日志列表（按创建时间升序）
     * @throws BusinessException 任务不存在时抛出
     */
    @Override
    public List<RsTaskLogVO> listLogs(Long taskId) {
        if (taskMapper.selectByIdForOwner(taskId, currentUserContext.getCurrentUserId()) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        return taskLogMapper.selectByTaskIdOrderByCreatedAt(taskId);
    }

    /**
     * 在事务内准备任务提交数据。
     *
     * <p>步骤：
     * <ol>
     *   <li>校验影像可访问且状态为 READY；</li>
     *   <li>使用 {@code imageMapper.markProcessingIfReady} 进行 CAS 式状态变更（READY -> PROCESSING），
     *   防止同一影像被重复提交处理任务；</li>
     *   <li>插入任务记录；</li>
     *   <li>写入任务输出路径；</li>
     *   <li>创建 Outbox 消息。</li>
     * </ol>
     *
     * @param submitDTO 任务提交参数
     * @return 准备好的任务数据（任务实体 + 输出路径 + Outbox ID）
     */
    private PreparedTask prepareTask(RsTaskSubmitDTO submitDTO) {
        String currentUserId = currentUserContext.getCurrentUserId();
        String clientRequestId = normalizeClientRequestId(submitDTO.getClientRequestId());
        if (!isBlank(clientRequestId)) {
            RsTask existingTask = taskMapper.selectByOwnerAndClientRequestId(currentUserId, clientRequestId);
            if (existingTask != null) {
                return new PreparedTask(existingTask, existingTask.getOutputObjectKey(), null, false);
            }
        }
        // 第一步：校验影像是否存在且当前用户有访问权限。影像按用户隔离，不可越权提交。
        RsImage image = imageMapper.selectAccessibleById(submitDTO.getImageId(), currentUserId);
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像不存在或无权访问");
        }
        // 第二步：影像必须处于 READY 状态才能提交处理任务。非 READY 状态的影像可能正在处理或已删除。
        if (!ImageStatus.fromDb(image.getStatus()).canSubmitTask()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只有 READY 状态的影像可以提交处理任务");
        }
        Map<String, Object> taskParams = ensureMutableParams(submitDTO);
        if (submitDTO.getTaskType() == RemoteSensingTaskMessage.TaskType.CHANGE_DETECTION) {
            validateAndFillChangeDetectionParams(image, currentUserId, taskParams);
        } else {
            imageBandCapabilityService.validateAndFillTaskParams(image, submitDTO.getTaskType(), taskParams);
        }

        // 第三步：CAS 式状态变更（READY -> PROCESSING）。使用 UPDATE ... WHERE status='READY' 保证
        // 同一影像不会被并发创建多个任务。返回影响行数 <=0 说明状态已被其他线程抢先变更。
        if (imageMapper.markProcessingIfReady(image.getId()) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像当前状态不允许提交处理任务");
        }

        // 第四步：创建任务记录，初始状态为 PENDING。
        RsTask task = buildPendingTask(submitDTO, currentUserId);
        taskMapper.insert(task);

        // 第五步：预计算结果文件路径，按 任务类型/年/月/task_{id}.tif 组织。
        String outputObjectKey = buildOutputObjectKey(submitDTO.getTaskType().name(), task.getId());
        taskMapper.updateOutputObject(task.getId(), minioProperties.getBucketName(), outputObjectKey);
        // 第六步：创建 Outbox 消息（数据库级别），消息投递在事务外执行。
        Long outboxId = messageOutboxService.createTaskMessage(
                task.getId(),
                buildMessage(submitDTO, image, task.getId(), outputObjectKey)
        );
        return new PreparedTask(task, outputObjectKey, outboxId, true);
    }

    private RsTaskSubmitVO resolveDuplicateSubmit(RsTaskSubmitDTO submitDTO, DuplicateKeyException exception) {
        String clientRequestId = normalizeClientRequestId(submitDTO.getClientRequestId());
        if (isBlank(clientRequestId)) {
            throw exception;
        }
        RsTask existingTask = taskMapper.selectByOwnerAndClientRequestId(
                currentUserContext.getCurrentUserId(),
                clientRequestId
        );
        if (existingTask == null) {
            throw exception;
        }
        return new RsTaskSubmitVO(existingTask.getId());
    }

    private Map<String, Object> ensureMutableParams(RsTaskSubmitDTO submitDTO) {
        Map<String, Object> params = submitDTO.getParams();
        if (params == null) {
            params = new LinkedHashMap<>();
        } else if (!(params instanceof LinkedHashMap)) {
            params = new LinkedHashMap<>(params);
        }
        submitDTO.setParams(params);
        return params;
    }

    /**
     * 校验变化检测两期影像权限和基础兼容性，并由后端填充 MinIO 对象路径。
     */
    private void validateAndFillChangeDetectionParams(RsImage afterImage,
                                                      String currentUserId,
                                                      Map<String, Object> params) {
        Long beforeImageId = requiredLongParam(params, "beforeImageId");
        if (beforeImageId.equals(afterImage.getId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "变化检测需要选择两期不同影像");
        }

        RsImage beforeImage = imageMapper.selectAccessibleById(beforeImageId, currentUserId);
        if (beforeImage == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "对比前影像不存在或无权访问");
        }
        if (!ImageStatus.fromDb(beforeImage.getStatus()).canSubmitTask()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "对比前影像必须处于 READY 状态");
        }

        int band = optionalIntParam(params, "band", 1);
        validateChangeDetectionBand(beforeImage, afterImage, band);
        validateChangeDetectionCompatibility(beforeImage, afterImage);

        params.remove("beforeObjectKey");
        params.remove("afterObjectKey");
        params.remove("beforeBucket");
        params.remove("afterBucket");
        params.put("beforeImageId", beforeImage.getId());
        params.put("afterImageId", afterImage.getId());
        params.put("beforeBucket", beforeImage.getMinioBucket());
        params.put("afterBucket", afterImage.getMinioBucket());
        params.put("beforeObjectKey", beforeImage.getObjectKey());
        params.put("afterObjectKey", afterImage.getObjectKey());
        params.put("band", band);
    }

    /**
     * 校验变化检测波段编号是否同时存在于前后两期影像中。
     */
    private void validateChangeDetectionBand(RsImage beforeImage, RsImage afterImage, int band) {
        if (band < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "变化检测波段编号必须大于 0");
        }
        int beforeBandCount = requireBandCount(beforeImage, "对比前影像");
        int afterBandCount = requireBandCount(afterImage, "检测后影像");
        if (band > beforeBandCount) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "变化检测波段编号不能大于对比前影像波段数 " + beforeBandCount);
        }
        if (band > afterBandCount) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "变化检测波段编号不能大于检测后影像波段数 " + afterBandCount);
        }
    }

    /**
     * 校验变化检测两期影像的尺寸和 CRS，避免 Worker 下载后才发现无法配准。
     */
    private void validateChangeDetectionCompatibility(RsImage beforeImage, RsImage afterImage) {
        if (!sameValue(beforeImage.getWidth(), afterImage.getWidth())
                || !sameValue(beforeImage.getHeight(), afterImage.getHeight())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "变化检测两期影像尺寸不一致");
        }
        if (!sameValue(beforeImage.getProjection(), afterImage.getProjection())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "变化检测两期影像坐标系不一致");
        }
    }

    private int requireBandCount(RsImage image, String label) {
        if (image.getBandCount() == null || image.getBandCount() < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), label + "缺少有效波段数");
        }
        return image.getBandCount();
    }

    private Long requiredLongParam(Map<String, Object> params, String paramName) {
        Object value = params.get(paramName);
        if (value == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "CHANGE_DETECTION 需要 params." + paramName);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException exception) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须是整数");
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 不能为空");
    }

    private int optionalIntParam(Map<String, Object> params, String paramName, int defaultValue) {
        Object value = params.get(paramName);
        if (value == null || (value instanceof String string && string.isBlank())) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException exception) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须是整数");
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须是整数");
    }

    private boolean sameValue(Object left, Object right) {
        if (left == null || right == null) {
            return true;
        }
        return left.equals(right);
    }

    /**
     * 在事务外发布 Outbox 消息到 RabbitMQ。
     *
     * <p>投递失败不抛异常，只记录 WARN 日志，等待定时调度器 {@code publishDueMessages} 补偿重试。
     * 这样设计的目的是：消息投递失败不应影响任务提交的返回响应给客户端。
     */
    private void publishTaskMessage(PreparedTask preparedTask) {
        try {
            messageOutboxService.publishById(preparedTask.outboxId());
        } catch (RuntimeException exception) {
            log.warn("Outbox 立即投递失败，等待定时补偿，taskId={}, outboxId={}, reason={}",
                    preparedTask.task().getId(), preparedTask.outboxId(), exception.getMessage());
        }
    }

    /**
     * Worker 抢占任务的事务内实现。
     *
     * <p>抢占逻辑：
     * <ol>
     *   <li>终态任务直接返回 {@code ALREADY_FINISHED}，不修改状态；</li>
     *   <li>RUNNING 状态任务直接返回 {@code ALREADY_RUNNING}，避免重复抢占；</li>
     *   <li>PENDING 或其他可流转状态：使用 CAS 式 UPDATE
     *   （{@code taskMapper.claimForRunning}，WHERE status=oldStatus）尝试变更为 RUNNING；
     *   更新行数 = 0 说明已被其他 Worker 抢先。</li>
     * </ol>
     * 使用 CAS 而不是 SELECT ... FOR UPDATE 的原因是：避免行锁带来的数据库连接池竞争，
     * 且 Worker 抢占场景下冲突概率低，CAS 失败后重试成本远低于锁等待。
     */
    private RsTaskClaimVO claimInTransaction(Long taskId) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }

        TaskStatus currentStatus = TaskStatus.fromDb(task.getStatus());
        // 终态任务不需要再处理，直接返回当前状态信息。
        if (currentStatus.isTerminal()) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_FINISHED.dbValue(), task.getStatus(), "任务已进入终态，跳过重复处理", task.getOutputObjectKey());
        }
        // 已经在运行的任务不需要重复抢占。
        if (currentStatus == TaskStatus.RUNNING) {
            return new RsTaskClaimVO(false, TaskClaimAction.ALREADY_RUNNING.dbValue(), task.getStatus(), "任务正在运行，跳过本次重复投递", task.getOutputObjectKey());
        }

        // CAS 式抢占：只有 status 仍然等于当前值时才更新为 RUNNING。
        int updated = taskMapper.claimForRunning(taskId);
        if (updated <= 0) {
            // CAS 失败，重新查询最新状态返回给调用方，便于调用方决策重试。
            RsTask latestTask = taskMapper.selectById(taskId);
            String latestStatus = latestTask == null ? task.getStatus() : latestTask.getStatus();
            return new RsTaskClaimVO(false, TaskClaimAction.CLAIM_REJECTED.dbValue(), latestStatus, "任务状态已变化，抢占失败", task.getOutputObjectKey());
        }

        // 抢占成功，记录操作日志。
        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel("INFO");
        taskLog.setMessage("Worker 抢占任务成功：" + task.getStatus() + " -> " + TaskStatus.RUNNING.dbValue());
        taskLog.setDetail(toJson(Map.of("fromStatus", task.getStatus(), "toStatus", TaskStatus.RUNNING.dbValue())));
        taskLogMapper.insert(taskLog);
        return new RsTaskClaimVO(true, TaskClaimAction.CLAIMED.dbValue(), TaskStatus.RUNNING.dbValue(), "任务抢占成功", task.getOutputObjectKey());
    }

    /**
     * 事务内更新任务状态。
     *
     * <p>严格防护：
     * <ul>
     *   <li>状态流转校验：使用 {@link TaskStatus#canTransitTo} 保证只能按预定义路径流转；</li>
     *   <li>CAS 更新：{@code UPDATE ... WHERE status=oldStatus} 防止并发覆盖；</li>
     *   <li>终态联动：SUCCESS -> 创建结果文件 + 注册 GeoServer 发布回调；
     *   任意终态 -> 释放影像 PROCESSING 锁；</li>
     *   <li>SUCCESS 必须提供 outputObjectKey，FAILED 必须提供 errorMessage。</li>
     * </ul>
     */
    private void updateStatusInTransaction(Long taskId, RsTaskStatusUpdateDTO updateDTO) {
        RsTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }

        // 第一步：校验目标状态合法性和状态流转可行性。
        TaskStatus targetStatus = normalizeStatus(updateDTO.getStatus());
        TaskStatus currentStatus = TaskStatus.fromDb(task.getStatus());
        validateTransition(currentStatus, targetStatus);
        if (isDuplicateTerminalCallback(currentStatus, targetStatus)) {
            return;
        }
        // 第二步：解析错误信息。对于 SUCCESS，errorMessage 可能为空；对于 FAILED，errorMessage 必须存在。
        String errorMessage = resolveErrorMessage(targetStatus, updateDTO);
        if (targetStatus == TaskStatus.SUCCESS && isBlank(updateDTO.getOutputObjectKey()) && isBlank(task.getOutputObjectKey())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "SUCCESS 状态必须提供 outputObjectKey");
        }
        validateSuccessOutputObjectKey(task, updateDTO, targetStatus);
        if (targetStatus == TaskStatus.FAILED && isBlank(errorMessage)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "FAILED 状态必须提供 errorMessage");
        }

        // 第三步：CAS 式更新。WHERE status=当前状态 保证并发安全。
        // 使用 CAS 而不是悲观锁的原因：Worker 回调冲突概率低，CAS 失败后客户端重试比行锁更高效。
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
        // 第四步：记录状态变更日志。
        insertStatusLog(task, targetStatus.dbValue(), updateDTO, errorMessage);
        // 第五步：SUCCESS 联动操作 -- 创建结果文件并注册 GeoServer 发布。
        if (targetStatus == TaskStatus.SUCCESS) {
            createOrUpdateResultFile(task, updateDTO);
            // 将 GeoServer 发布注册为 afterCommit 回调，避免长事务阻塞。
            registerGeoServerPublishAfterCommit(taskId);
        }
        // 第六步：任务进入终态后，尝试释放影像的 PROCESSING 状态锁。
        releaseImageIfTaskFinished(task, targetStatus);
    }

    private boolean isDuplicateTerminalCallback(TaskStatus currentStatus, TaskStatus targetStatus) {
        return currentStatus.isTerminal() && currentStatus == targetStatus;
    }

    private void validateSuccessOutputObjectKey(RsTask task,
                                                RsTaskStatusUpdateDTO updateDTO,
                                                TaskStatus targetStatus) {
        if (targetStatus != TaskStatus.SUCCESS
                || isBlank(task.getOutputObjectKey())
                || isBlank(updateDTO.getOutputObjectKey())) {
            return;
        }
        if (!task.getOutputObjectKey().equals(updateDTO.getOutputObjectKey())) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR.getCode(),
                    "SUCCESS 回调的 outputObjectKey 与任务预期输出路径不一致"
            );
        }
    }

    private RsTask buildPendingTask(RsTaskSubmitDTO submitDTO, String ownerId) {
        RsTask task = new RsTask();
        task.setOwnerId(ownerId);
        task.setClientRequestId(normalizeClientRequestId(submitDTO.getClientRequestId()));
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

    /**
     * 构造发送给 RabbitMQ Worker 的任务消息。
     *
     * <p>消息只传递 MinIO 对象路径和业务参数，不传递文件二进制内容。
     * Worker 收到消息后自行从 MinIO 下载输入文件进行处理。
     */
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

    /**
     * 注册事务提交后的 GeoServer 发布回调。
     *
     * <p>使用 {@link TransactionSynchronization#afterCommit()} 而非直接在事务内调用 GeoServer API 的原因：
     * <ul>
     *   <li>GeoServer REST API 调用可能涉及网络 I/O，放在事务内会长时间占用数据库连接；</li>
     *   <li>GeoServer 发布失败不应回滚任务状态的数据库更新（任务状态已经成功写入）；</li>
     *   <li>事务提交后才执行，保证数据库状态对外可见，其他组件不会读到不一致的状态。</li>
     * </ul>
     * 如果当前没有活跃事务同步（@see TransactionSynchronizationManager），则直接异步调用。
     */
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

    /**
     * 任务进入终态时尝试释放影像的 PROCESSING 状态锁。
     *
     * <p>只有当同一影像的所有活跃任务都已完成（active count == 0）时，
     * 才将影像从 PROCESSING 恢复为 READY 状态。
     * 这样做防止一张影像有多个任务时，前一个任务完成就过早释放锁。
     */
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

    private String normalizeClientRequestId(String clientRequestId) {
        if (isBlank(clientRequestId)) {
            return null;
        }
        return clientRequestId.trim();
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

    private record PreparedTask(RsTask task, String outputObjectKey, Long outboxId, boolean publishRequired) {
    }
}
