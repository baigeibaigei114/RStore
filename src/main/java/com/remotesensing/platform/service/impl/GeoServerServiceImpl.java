package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.enums.ResultFileStatus;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.config.properties.GeoServerProperties;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.GeoServerPublishVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * GeoServer 发布服务实现类，负责将遥感任务处理结果（GeoTIFF）发布为 GeoServer 图层，
 * 并通过 WMS / WCS 标准 OGC 服务对外提供。
 *
 * <p>核心职责：
 * <ol>
 *   <li>在 GeoServer 中创建工作空间（workspace）。</li>
 *   <li>将 GeoTIFF 文件通过外部引用方式注册为 Coverage Store。</li>
 *   <li>支持同步发布和异步发布两种模式，异步模式下使用独立线程池执行。</li>
 *   <li>状态机管理：PENDING_PUBLISH -> PUBLISHING -> PUBLISHED / PUBLISH_FAILED。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>GeoTIFF 通过 GeoServer 共享目录（Shared Data Directory）的本地文件路径访问，
 *       而非 HTTP 下载 URL，避免依赖临时预签名 URL 的可用性。</li>
 *   <li>发布前检查任务状态必须为 SUCCESS，且校验任务所属权（enforceOwner 模式）。</li>
 *   <li>通过{@code "configure=all"} 让 GeoServer 在注册 Coverage Store 时自动创建图层。</li>
 *   <li>使用乐观锁（{@code markPublishing} 的 SQL 行级条件）防止并发重复发布同一结果。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Service
public class GeoServerServiceImpl implements GeoServerService {

    private static final Logger log = LoggerFactory.getLogger(GeoServerServiceImpl.class);

    private final RestClient geoServerRestClient;
    private final GeoServerProperties geoServerProperties;
    private final RsTaskMapper taskMapper;
    private final RsResultFileMapper resultFileMapper;
    private final MinioService minioService;
    private final Executor geoServerPublishExecutor;
    private final CurrentUserContext currentUserContext;

    public GeoServerServiceImpl(@Qualifier("geoServerRestClient") RestClient geoServerRestClient,
                                GeoServerProperties geoServerProperties,
                                RsTaskMapper taskMapper,
                                RsResultFileMapper resultFileMapper,
                                MinioService minioService,
                                @Qualifier("geoServerPublishExecutor") Executor geoServerPublishExecutor,
                                CurrentUserContext currentUserContext) {
        this.geoServerRestClient = geoServerRestClient;
        this.geoServerProperties = geoServerProperties;
        this.taskMapper = taskMapper;
        this.resultFileMapper = resultFileMapper;
        this.minioService = minioService;
        this.geoServerPublishExecutor = geoServerPublishExecutor;
        this.currentUserContext = currentUserContext;
    }

    /**
     * 在 GeoServer 中创建工作空间（workspace）。
     *
     * <p>如果 workspace 已存在（GeoServer 返回 409 Conflict），视为成功，不做额外处理。
     * 这是幂等操作，多次调用不会产生副作用。
     *
     * @param workspace 工作空间名称
     */
    @Override
    public void createWorkspace(String workspace) {
        String resolvedWorkspace = requireName(workspace, "workspace");
        // 构建 GeoServer REST API 请求体：{"workspace":{"name":"xxx"}}
        Map<String, Object> requestBody = Map.of("workspace", Map.of("name", resolvedWorkspace));

        geoServerRestClient.post()
                .uri("/rest/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .exchange((request, response) -> {
                    // 201 Created 表示新建成功；409 Conflict 表示已存在，均视为成功（幂等）
                    if (isSuccessOrAlreadyExists(response.getStatusCode())) {
                        return null;
                    }
                    throw geoServerException("创建 GeoServer workspace 失败", response.getStatusCode(), readBody(response));
                });
    }

    /**
     * 在 GeoServer 中通过外部 GeoTIFF 引用创建 Coverage Store，并自动配置图层。
     *
     * <p>使用 HTTP PUT 请求注册外部 GeoTIFF，参数 {@code configure=all} 指示 GeoServer
     * 在创建 Coverage Store 的同时自动解析 GeoTIFF 元数据并创建对应的 Layer。
     * 如果同名的 Coverage Store 和 Layer 已存在（409 Conflict + layerExists 校验），视为成功。
     *
     * <p>为什么使用 {@code file://} URL 而非 HTTP URL？
     * 因为 GeoServer 与后端服务运行在同一主机（或同一容器卷挂载路径），
     * 通过共享目录本地文件路径访问 GeoTIFF 比 HTTP 下载更稳定且不依赖临时签名 URL。
     *
     * @param workspace       GeoServer 工作空间名称
     * @param storeName       Coverage Store 名称
     * @param layerName       图层名称
     * @param geoTiffLocation GeoTIFF 文件的 file:// URL 路径
     */
    @Override
    public void createCoverageStore(String workspace, String storeName, String layerName, String geoTiffLocation) {
        String resolvedWorkspace = requireName(workspace, "workspace");
        String resolvedStoreName = requireName(storeName, "storeName");
        String resolvedLayerName = requireName(layerName, "layerName");
        if (geoTiffLocation == null || geoTiffLocation.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoTIFF 访问位置不能为空");
        }

        // 使用 GeoServer 容器内可见的 file URL 注册外部 GeoTIFF。
        // 为什么选择 file URL 而非 S3/Minio 预签名 URL？
        // 因为 GeoServer 与后端服务通过共享卷（shared data directory）通信，
        // file URL 无网络开销和签名过期问题，路径一旦配置持续可用。
        // configure=all 让 GeoServer 自动创建 Layer，避免额外 REST 调用。
        // coverageName 指定 GeoServer 内部使用的图层名。
        geoServerRestClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/rest/workspaces/{workspace}/coveragestores/{storeName}/external.geotiff")
                        .queryParam("configure", "all")
                        .queryParam("coverageName", resolvedLayerName)
                        .build(resolvedWorkspace, resolvedStoreName))
                .contentType(MediaType.TEXT_PLAIN)
                .body(geoTiffLocation)
                .exchange((request, response) -> {
                    HttpStatusCode statusCode = response.getStatusCode();
                    if (statusCode.is2xxSuccessful()) {
                        return null;
                    }
                    // 409 Conflict + 图层已存在 = 幂等成功场景
                    // 当重复发布同一 GeoTIFF 时，GeoServer 返回 409 但 Layer 实际上已经注册
                    if (statusCode.value() == 409 && layerExists(resolvedWorkspace, resolvedLayerName)) {
                        return null;
                    }
                    throw geoServerException("创建 GeoServer coverage store 失败", statusCode, readBody(response));
                });
    }

    /**
     * 同步发布任务结果为 GeoServer 图层。
     *
     * <p>该方法以调用者所属权校验（enforceOwner=true）的方式执行完整的发布流程。
     * 如果在发布过程中抛出异常（网络异常、文件不存在、GeoServer 拒绝等），
     * 会通过 {@code resultFileMapper.markPublishFailed} 将状态标记为失败并记录错误原因。
     *
     * <p>事务属性：非事务。因为涉及 GeoServer 远程 HTTP 调用（createWorkspace、createCoverageStore），
     * 这些调用不能回滚。DB 状态更新作为补偿机制而非事务保护。
     *
     * @param taskId 遥感任务 ID
     * @return 发布结果视图对象，包含 WMS / WCS URL 等信息
     */
    @Override
    public GeoServerPublishVO publishTaskResult(Long taskId) {
        return publishTaskResultInternal(taskId, true);
    }

    /**
     * 发布任务结果为 GeoServer 图层的完整内部实现。
     *
     * <p>发布状态机：PENDING_PUBLISH ->（markPublishing）-> PUBLISHING ->（markPublished）-> PUBLISHED
     * 任一环节失败则调用 {@code markPublishFailed} 回退到 PUBLISH_FAILED 状态。
     *
     * <p>乐观锁设计：通过 SQL 条件 {@code status = PENDING_PUBLISH} 保证 {@code markPublishing} 的原子性，
     * 只有第一个获得更新的线程能继续执行发布，后续线程直接抛异常退出。
     * 这避免了同一结果文件被并发多次发布到 GeoServer。
     *
     * @param taskId       遥感任务 ID
     * @param enforceOwner 是否校验任务所属权。同步发布时校验，异步发布时不校验（异步由原提交者触发，上下文可能已变化）
     * @return 发布结果视图对象
     */
    private GeoServerPublishVO publishTaskResultInternal(Long taskId, boolean enforceOwner) {
        // Step 1: 校验任务存在、状态为 SUCCESS、且（按模式）属主匹配
        RsTaskVO task = validatePublishableTask(taskId, enforceOwner);
        // Step 2: 查找或创建结果文件记录
        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile == null) {
            resultFile = buildResultFileFromTask(task);
            resultFileMapper.insert(resultFile);
            // 重新查询以获取数据库填充的默认值和自增 ID
            resultFile = resultFileMapper.selectByTaskId(taskId);
        }
        if (resultFile == null) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "创建任务结果文件记录失败");
        }
        // Step 3: 如果已发布则直接返回，不做重复发布
        if (ResultFileStatus.fromDb(resultFile.getStatus()) == ResultFileStatus.PUBLISHED) {
            return buildPublishVO(task, resultFile);
        }
        // Step 4: 乐观锁抢占发布权 —— 只有 PENDING_PUBLISH 状态才能更新为 PUBLISHING
        // 如果返回 <=0 说明被其他线程/实例抢占了，直接抛出异常
        if (resultFileMapper.markPublishing(resultFile.getId()) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果文件正在发布或状态不允许发布");
        }

        try {
            // Step 5: 准备发布参数
            String workspace = requireName(geoServerProperties.getWorkspace(), "workspace");
            String layerName = buildLayerName(task);
            String storeName = layerName + "_store";
            // 从 Minio 下载 GeoTIFF 到 GeoServer 共享目录并返回 file URL
            String geoTiffLocation = prepareSharedGeoTiff(resultFile.getObjectKey());
            String wmsUrl = buildWmsUrl(workspace, layerName);
            String wcsUrl = buildWcsUrl(workspace, layerName);

            // Step 6: 执行发布 —— 先创建 workspace（幂等），再注册外部 GeoTIFF
            createWorkspace(workspace);
            createCoverageStore(workspace, storeName, layerName, geoTiffLocation);
            // Step 7: 标记发布成功，写入 workspace/store/layer 及服务 URL
            resultFileMapper.markPublished(resultFile.getId(), workspace, storeName, layerName, wmsUrl, wcsUrl);

            RsResultFile published = resultFileMapper.selectById(resultFile.getId());
            return buildPublishVO(task, published == null ? resultFile : published);
        } catch (RuntimeException exception) {
            // Step 8: 发布异常时回退状态并记录错误原因，保证 DB 状态可见性
            resultFileMapper.markPublishFailed(resultFile.getId(), truncate(exception.getMessage()));
            throw exception;
        }
    }

    /**
     * 异步发布任务结果为 GeoServer 图层。
     *
     * <p>将发布任务提交到 {@link #geoServerPublishExecutor} 线程池中执行。
     * 如果线程池队列已满（{@link java.util.concurrent.RejectedExecutionException}），
     * 直接将结果文件状态标记为发布失败，不会阻塞调用方。
     *
     * <p>异步模式在 {@link #publishTaskResultSafely(Long)} 中捕获所有非受检异常，
     * 避免线程池中的未捕获异常导致线程终止。
     *
     * @param taskId 遥感任务 ID
     */
    @Override
    public void publishTaskResultAsync(Long taskId) {
        try {
            // 提交到独立线程池异步执行，不阻塞当前请求线程
            geoServerPublishExecutor.execute(() -> publishTaskResultSafely(taskId));
        } catch (RejectedExecutionException exception) {
            // 线程池已满（队列满 + 最大线程数达到）时直接标记失败，不重试
            markPublishFailedByTaskId(taskId, "GeoServer 发布线程池已满：" + exception.getMessage());
            log.warn("GeoServer 发布任务提交失败，taskId={}, reason={}", taskId, exception.getMessage());
        }
    }

    /**
     * 安全地异步发布任务结果，捕获并记录所有运行时异常以避免线程池中的线程因未捕获异常而终止。
     * <p>在异步模式下调用 {@code publishTaskResultInternal} 时不校验任务所属权（enforceOwner=false），
     * 因为异步执行时当前用户上下文可能已变更或已过期。
     */
    private void publishTaskResultSafely(Long taskId) {
        try {
            publishTaskResultInternal(taskId, false);
        } catch (RuntimeException exception) {
            log.warn("GeoServer 异步发布失败，taskId={}, reason={}", taskId, exception.getMessage(), exception);
        }
    }

    /**
     * 校验任务是否可以被发布：
     * <ul>
     *   <li>任务必须存在</li>
     *   <li>{@code enforceOwner=true} 时校验当前用户是否为任务属主</li>
     *   <li>任务状态必须为 SUCCESS</li>
     *   <li>任务结果文件 key 不能为空</li>
     * </ul>
     *
     * @param taskId       任务 ID
     * @param enforceOwner 是否强制校验任务所属权
     * @return 任务详情视图对象
     */
    private RsTaskVO validatePublishableTask(Long taskId, boolean enforceOwner) {
        RsTaskVO task = taskMapper.selectDetailById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        if (enforceOwner && !currentUserContext.getCurrentUserId().equals(task.getOwnerId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "无权发布该任务结果图层");
        }
        if (TaskStatus.fromDb(task.getStatus()) != TaskStatus.SUCCESS) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只有 SUCCESS 状态的任务结果可以发布为 GeoServer 图层");
        }
        if (task.getOutputObjectKey() == null || task.getOutputObjectKey().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果文件 objectKey 为空，不能发布图层");
        }
        return task;
    }

    /**
     * 将指定任务的结果文件状态标记为发布失败。
     * <p>用于异步发布时线程池满的场景，此时尚未创建 resultFile 记录，需要先查询再更新。
     */
    private void markPublishFailedByTaskId(Long taskId, String errorMessage) {
        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile != null) {
            resultFileMapper.markPublishFailed(resultFile.getId(), truncate(errorMessage));
        }
    }

    /**
     * 从任务视图对象构建结果文件实体。
     * <p>初始状态为 PENDING_PUBLISH，可见性为 PRIVATE（发布后由 {@link com.remotesensing.platform.service.LayerService} 管理可见性变更）。
     *
     * @param task 任务详情视图对象
     * @return 结果文件实体
     */
    private RsResultFile buildResultFileFromTask(RsTaskVO task) {
        RsResultFile resultFile = new RsResultFile();
        resultFile.setOwnerId(task.getOwnerId());
        resultFile.setVisibility(Visibility.PRIVATE.dbValue());
        resultFile.setTaskId(task.getId());
        resultFile.setImageId(task.getImageId());
        resultFile.setFileName(extractFilename(task.getOutputObjectKey()));
        resultFile.setFileType("GEOTIFF");
        resultFile.setMinioBucket(task.getOutputBucket());
        resultFile.setObjectKey(task.getOutputObjectKey());
        resultFile.setMimeType("image/tiff");
        resultFile.setStatus(ResultFileStatus.PENDING_PUBLISH.dbValue());
        return resultFile;
    }

    /**
     * 准备 GeoServer 共享目录中的 GeoTIFF 文件。
     *
     * <p>如果共享目录中目标文件已存在则跳过下载（避免重复 IO），
     * 否则从 Minio 下载到临时 {@code .part} 文件后原子性地重命名为目标文件。
     * 为什么用 {@code .part} 临时文件？防止下载过程中程序崩溃产生不完整的 GeoTIFF，
     * 导致 GeoServer 读取到损坏文件。
     *
     * <p>同时校验 objectKey 的合法性，防止路径穿越攻击。
     *
     * @param objectKey Minio 中存储的对象键
     * @return GeoServer 可访问的 file:// URL
     */
    private String prepareSharedGeoTiff(String objectKey) {
        validateResultObjectKey(objectKey);
        Path localRoot = Paths.get(geoServerProperties.getSharedDataLocalDir()).toAbsolutePath().normalize();
        Path localPath = localRoot.resolve(objectKey).normalize();
        if (!localPath.startsWith(localRoot)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果文件 objectKey 格式不合法");
        }

        if (!Files.exists(localPath)) {
            Path tempPath = localPath.resolveSibling(localPath.getFileName() + ".part");
            try {
                minioService.downloadObject(objectKey, tempPath);
                Files.move(tempPath, localPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                deleteTempFileQuietly(tempPath);
                throw new BusinessException(ResultCode.FAIL.getCode(), "准备 GeoServer 共享 GeoTIFF 失败：" + exception.getMessage());
            } catch (RuntimeException exception) {
                deleteTempFileQuietly(tempPath);
                throw exception;
            }
        }
        return buildGeoServerFileUrl(objectKey);
    }

    /**
     * 静默删除临时文件，删除失败仅记录警告日志。
     * <p>用于在 GeoTIFF 下载或移动过程中发生异常时的清理工作。
     */
    private void deleteTempFileQuietly(Path tempPath) {
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException exception) {
            log.warn("清理 GeoServer 共享目录临时文件失败，path={}, reason={}", tempPath, exception.getMessage());
        }
    }

    /**
     * 校验结果文件 objectKey 的合法性。
     *
     * <p>限制：
     * <ul>
     *   <li>不能包含 {@code ..}（防止路径穿越）</li>
     *   <li>不能以 {@code /} 开头（防止绝对路径引用）</li>
     *   <li>不能包含 {@code \}（限定 Unix 风格路径）</li>
     *   <li>必须以 {@code result/} 开头（限制发布范围，防止误发布其他目录文件）</li>
     * </ul>
     */
    private void validateResultObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果文件 objectKey 不能为空");
        }
        if (objectKey.contains("..") || objectKey.startsWith("/") || objectKey.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果文件 objectKey 格式不合法");
        }
        if (!objectKey.startsWith("result/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoServer 只允许发布 result/ 目录下的处理结果");
        }
    }

    /**
     * 构建 GeoServer 可访问的 {@code file://} URL。
     * <p>将共享目录的容器内部路径与 objectKey 拼接，
     * 替换反斜杠为正向斜杠，并编码空格为 {@code %20}。
     *
     * @param objectKey 相对路径，如 {@code result/xxx.tif}
     * @return 完整 file URL，如 {@code file:///opt/geoserver/data/result/xxx.tif}
     */
    private String buildGeoServerFileUrl(String objectKey) {
        String root = requireName(geoServerProperties.getSharedDataGeoServerDir(), "sharedDataGeoServerDir")
                .replace("\\", "/");
        String normalizedRoot = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        String path = normalizedRoot + "/" + objectKey.replace("\\", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // 空格编码，避免 file URL 中的空格导致 GeoServer 解析失败
        return "file://" + path.replace(" ", "%20");
    }

    /**
     * 构建发布结果视图对象，包含 WMS / WCS 访问 URL 和图层预览 URL 等完整信息。
     */
    private GeoServerPublishVO buildPublishVO(RsTaskVO task, RsResultFile resultFile) {
        GeoServerPublishVO vo = new GeoServerPublishVO();
        vo.setTaskId(task.getId());
        vo.setOwnerId(task.getOwnerId());
        vo.setVisibility(resultFile.getVisibility());
        vo.setWorkspace(resultFile.getWorkspace());
        vo.setStoreName(resultFile.getStoreName());
        vo.setLayerName(resultFile.getLayerName());
        vo.setQualifiedLayerName(qualifiedLayerName(resultFile.getWorkspace(), resultFile.getLayerName()));
        vo.setSourceObjectKey(resultFile.getObjectKey());
        vo.setWmsUrl(resultFile.getWmsUrl());
        vo.setWcsUrl(resultFile.getWcsUrl());
        vo.setLayerPreviewUrl(buildLayerPreviewUrl(resultFile.getWorkspace(), resultFile.getLayerName()));
        return vo;
    }

    /**
     * 构造 GeoServer REST API 调用失败的异常，包含 HTTP 状态码和响应体以便排查。
     */
    private BusinessException geoServerException(String message, HttpStatusCode statusCode, String responseBody) {
        return new BusinessException(ResultCode.FAIL.getCode(),
                "%s，status=%s，response=%s".formatted(message, statusCode.value(), responseBody));
    }

    /**
     * 判断 HTTP 状态码表示成功（2xx）或资源已存在（409 Conflict）。
     * <p>用于 createWorkspace / createCoverageStore 等幂等操作。
     */
    private boolean isSuccessOrAlreadyExists(HttpStatusCode statusCode) {
        return statusCode.is2xxSuccessful() || statusCode.value() == 409;
    }

    /**
     * 查询 GeoServer 中指定图层是否已存在。
     *
     * @param workspace 工作空间
     * @param layerName 图层名
     * @return true 如果图层存在；false 如果返回 404；其他异常则抛出
     */
    private boolean layerExists(String workspace, String layerName) {
        return geoServerRestClient.get()
                .uri("/rest/layers/{qualifiedLayerName}.json", qualifiedLayerName(workspace, layerName))
                .exchange((request, response) -> {
                    HttpStatusCode statusCode = response.getStatusCode();
                    if (statusCode.is2xxSuccessful()) {
                        return true;
                    }
                    if (statusCode.value() == 404) {
                        return false;
                    }
                    throw geoServerException("查询 GeoServer layer 失败", statusCode, readBody(response));
                });
    }

    /**
     * 读取 {@link org.springframework.http.client.ClientHttpResponse} 的响应体为字符串。
     */
    private String readBody(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    /**
     * 校验字符串参数不能为空或空白，返回去除首尾空格的字符串。
     *
     * @param value     参数值
     * @param fieldName 字段名（用于错误提示）
     * @return 去除空白后的字符串
     */
    private String requireName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), fieldName + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 为任务构建 GeoServer 图层名。
     * <p>格式：{@code task_{taskId}_{taskType}}，全部小写，
     * 只保留字母、数字和下划线。这是为了满足 GeoServer 对图层名字符集的限制。
     */
    private String buildLayerName(RsTaskVO task) {
        String taskType = task.getTaskType() == null ? "result" : task.getTaskType();
        String rawName = "task_%s_%s".formatted(task.getId(), taskType);
        return rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * 构建图层的 WMS 访问 URL（包含 service、version、request、layers 等必要参数）。
     */
    private String buildWmsUrl(String workspace, String layerName) {
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/wms?service=WMS&version=1.1.0&request=GetMap&layers="
                + qualifiedLayerName(workspace, layerName);
    }

    /**
     * 构建图层的 WCS 访问 URL（包含 service、version、request、coverageId 等必要参数）。
     */
    private String buildWcsUrl(String workspace, String layerName) {
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/wcs?service=WCS&version=2.0.1&request=GetCoverage&coverageId="
                + qualifiedLayerName(workspace, layerName);
    }

    /**
     * 构建 GeoServer Web UI 的图层预览页面 URL。
     * <p>使用 GeoServer 的 MapPreviewPage 路径，方便运维人员通过浏览器预览图层。
     * 如果 workspace 或 layerName 为 null，返回 null。
     */
    private String buildLayerPreviewUrl(String workspace, String layerName) {
        if (workspace == null || layerName == null) {
            return null;
        }
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/web/wicket/bookmarkable/org.geoserver.web.demo.MapPreviewPage?0&filter=false";
    }

    /**
     * 构造 GeoServer 限定的完整图层名，格式为 {@code workspace:layerName}。
     */
    private String qualifiedLayerName(String workspace, String layerName) {
        if (workspace == null || layerName == null) {
            return null;
        }
        return workspace + ":" + layerName;
    }

    /**
     * 去除 URL 末尾的斜杠，避免拼接路径时出现双斜杠。
     */
    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 从对象存储 key 中提取文件名部分。
     * <p>例如 {@code result/xxx/yyy.tif} -> {@code yyy.tif}。
     */
    private String extractFilename(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "result.tif";
        }
        int index = objectKey.lastIndexOf('/');
        return index >= 0 ? objectKey.substring(index + 1) : objectKey;
    }

    /**
     * 截断字符串到最大长度 1000 字符。
     * <p>用于限制存储到数据库的错误消息长度，避免因过长错误信息导致数据库插入或更新失败。
     */
    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
