package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.enums.ResultFileStatus;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.config.GeoServerProperties;
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

    public GeoServerServiceImpl(RestClient geoServerRestClient,
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

    @Override
    public void createWorkspace(String workspace) {
        String resolvedWorkspace = requireName(workspace, "workspace");
        Map<String, Object> requestBody = Map.of("workspace", Map.of("name", resolvedWorkspace));

        geoServerRestClient.post()
                .uri("/rest/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .exchange((request, response) -> {
                    if (isSuccessOrAlreadyExists(response.getStatusCode())) {
                        return null;
                    }
                    throw geoServerException("创建 GeoServer workspace 失败", response.getStatusCode(), readBody(response));
                });
    }

    @Override
    public void createCoverageStore(String workspace, String storeName, String layerName, String geoTiffLocation) {
        String resolvedWorkspace = requireName(workspace, "workspace");
        String resolvedStoreName = requireName(storeName, "storeName");
        String resolvedLayerName = requireName(layerName, "layerName");
        if (geoTiffLocation == null || geoTiffLocation.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoTIFF 访问位置不能为空");
        }

        // 使用 GeoServer 容器内可见的 file URL，避免发布数据源依赖短期预签名地址。
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
                    if (statusCode.value() == 409 && layerExists(resolvedWorkspace, resolvedLayerName)) {
                        return null;
                    }
                    throw geoServerException("创建 GeoServer coverage store 失败", statusCode, readBody(response));
                });
    }

    @Override
    public GeoServerPublishVO publishTaskResult(Long taskId) {
        return publishTaskResultInternal(taskId, true);
    }

    private GeoServerPublishVO publishTaskResultInternal(Long taskId, boolean enforceOwner) {
        RsTaskVO task = validatePublishableTask(taskId, enforceOwner);
        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile == null) {
            resultFile = buildResultFileFromTask(task);
            resultFileMapper.insert(resultFile);
            resultFile = resultFileMapper.selectByTaskId(taskId);
        }
        if (resultFile == null) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "创建任务结果文件记录失败");
        }
        if (ResultFileStatus.fromDb(resultFile.getStatus()) == ResultFileStatus.PUBLISHED) {
            return buildPublishVO(task, resultFile);
        }
        if (resultFileMapper.markPublishing(resultFile.getId()) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果文件正在发布或状态不允许发布");
        }

        try {
            String workspace = requireName(geoServerProperties.getWorkspace(), "workspace");
            String layerName = buildLayerName(task);
            String storeName = layerName + "_store";
            String geoTiffLocation = prepareSharedGeoTiff(resultFile.getObjectKey());
            String wmsUrl = buildWmsUrl(workspace, layerName);
            String wcsUrl = buildWcsUrl(workspace, layerName);

            createWorkspace(workspace);
            createCoverageStore(workspace, storeName, layerName, geoTiffLocation);
            resultFileMapper.markPublished(resultFile.getId(), workspace, storeName, layerName, wmsUrl, wcsUrl);

            RsResultFile published = resultFileMapper.selectById(resultFile.getId());
            return buildPublishVO(task, published == null ? resultFile : published);
        } catch (RuntimeException exception) {
            resultFileMapper.markPublishFailed(resultFile.getId(), truncate(exception.getMessage()));
            throw exception;
        }
    }

    @Override
    public void publishTaskResultAsync(Long taskId) {
        try {
            geoServerPublishExecutor.execute(() -> publishTaskResultSafely(taskId));
        } catch (RejectedExecutionException exception) {
            markPublishFailedByTaskId(taskId, "GeoServer 发布线程池已满：" + exception.getMessage());
            log.warn("GeoServer 发布任务提交失败，taskId={}, reason={}", taskId, exception.getMessage());
        }
    }

    private void publishTaskResultSafely(Long taskId) {
        try {
            publishTaskResultInternal(taskId, false);
        } catch (RuntimeException exception) {
            log.warn("GeoServer 异步发布失败，taskId={}, reason={}", taskId, exception.getMessage(), exception);
        }
    }

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

    private void markPublishFailedByTaskId(Long taskId, String errorMessage) {
        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile != null) {
            resultFileMapper.markPublishFailed(resultFile.getId(), truncate(errorMessage));
        }
    }

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

    private void deleteTempFileQuietly(Path tempPath) {
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException exception) {
            log.warn("清理 GeoServer 共享目录临时文件失败，path={}, reason={}", tempPath, exception.getMessage());
        }
    }

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

    private String buildGeoServerFileUrl(String objectKey) {
        String root = requireName(geoServerProperties.getSharedDataGeoServerDir(), "sharedDataGeoServerDir")
                .replace("\\", "/");
        String normalizedRoot = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        String path = normalizedRoot + "/" + objectKey.replace("\\", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "file://" + path.replace(" ", "%20");
    }

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

    private BusinessException geoServerException(String message, HttpStatusCode statusCode, String responseBody) {
        return new BusinessException(ResultCode.FAIL.getCode(),
                "%s，status=%s，response=%s".formatted(message, statusCode.value(), responseBody));
    }

    private boolean isSuccessOrAlreadyExists(HttpStatusCode statusCode) {
        return statusCode.is2xxSuccessful() || statusCode.value() == 409;
    }

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

    private String readBody(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    private String requireName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String buildLayerName(RsTaskVO task) {
        String taskType = task.getTaskType() == null ? "result" : task.getTaskType();
        String rawName = "task_%s_%s".formatted(task.getId(), taskType);
        return rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private String buildWmsUrl(String workspace, String layerName) {
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/wms?service=WMS&version=1.1.0&request=GetMap&layers="
                + qualifiedLayerName(workspace, layerName);
    }

    private String buildWcsUrl(String workspace, String layerName) {
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/wcs?service=WCS&version=2.0.1&request=GetCoverage&coverageId="
                + qualifiedLayerName(workspace, layerName);
    }

    private String buildLayerPreviewUrl(String workspace, String layerName) {
        if (workspace == null || layerName == null) {
            return null;
        }
        return trimTrailingSlash(geoServerProperties.getUrl())
                + "/web/wicket/bookmarkable/org.geoserver.web.demo.MapPreviewPage?0&filter=false";
    }

    private String qualifiedLayerName(String workspace, String layerName) {
        if (workspace == null || layerName == null) {
            return null;
        }
        return workspace + ":" + layerName;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String extractFilename(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "result.tif";
        }
        int index = objectKey.lastIndexOf('/');
        return index >= 0 ? objectKey.substring(index + 1) : objectKey;
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
