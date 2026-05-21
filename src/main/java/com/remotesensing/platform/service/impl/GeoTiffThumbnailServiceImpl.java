package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.PythonWorkerProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.GeoTiffThumbnailService;
import com.remotesensing.platform.service.MinioService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * GeoTIFF 缩略图生成服务实现类。
 *
 * <p>职责：调用 Python 工作进程生成 GeoTIFF 的缩略图 PNG，并将结果上传至 MinIO 对象存储。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>影像波段读取和直方图拉伸等计算密集型操作由 Python/rasterio 完成，Java 只做流程编排；</li>
 *   <li>临时文件统一在 finally 块中清理，保证不会残留临时 PNG 文件；</li>
 *   <li>即使 Python 脚本返回 {@code success: true}，仍校验 PNG 文件是否实际落盘，防止上传空路径。</li>
 * </ul>
 */
@Service
public class GeoTiffThumbnailServiceImpl implements GeoTiffThumbnailService {

    private static final String PNG_CONTENT_TYPE = "image/png";

    private final PythonWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final MinioService minioService;

    public GeoTiffThumbnailServiceImpl(PythonWorkerProperties properties,
                                       ObjectMapper objectMapper,
                                       MinioService minioService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.minioService = minioService;
    }

    /**
     * 生成指定 GeoTIFF 的缩略图，并上传至 MinIO。
     *
     * <p>流程：创建临时目录 -> 调用 Python 脚本生成 PNG -> 上传 PNG 到 MinIO -> 清理临时文件。</p>
     *
     * <p>关键约束：</p>
     * <ul>
     *   <li>临时文件和目录在 finally 中保证清理，避免磁盘空间泄漏；</li>
     *   <li>上传失败时直接抛出异常，由调用方处理；</li>
     *   <li>任何异常统一包装为 {@link BusinessException}。</li>
     * </ul>
     *
     * @param inputFile          源 GeoTIFF 文件路径
     * @param thumbnailObjectKey MinIO 上的目标对象键
     * @return 上传后的 MinIO 对象键
     * @throws BusinessException 如果缩略图生成或上传失败
     */
    @Override
    public String generateAndUpload(Path inputFile, String thumbnailObjectKey) {
        Path tempDir = null;
        Path thumbnailFile = null;
        try {
            tempDir = Files.createTempDirectory("rs-thumb-");
            thumbnailFile = tempDir.resolve("thumbnail.png");

            runPythonThumbnail(inputFile, thumbnailFile);
            minioService.uploadLocalFile(thumbnailFile, thumbnailObjectKey, PNG_CONTENT_TYPE);
            return thumbnailObjectKey;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 缩略图生成失败：" + exception.getMessage());
        } finally {
            deleteQuietly(thumbnailFile);
            deleteQuietly(tempDir);
        }
    }

    /**
     * 启动 Python 进程执行缩略图生成脚本。
     *
     * <p>Python 脚本接收两个参数：源 GeoTIFF 路径和输出 PNG 路径。脚本完成 PNG 生成后，
     * 通过 stdout 输出 {@code {"success": true/false}} 表示执行结果。</p>
     *
     * <p>校验逻辑：</p>
     * <ul>
     *   <li>超时退出并强制销毁进程；</li>
     *   <li>stdout 为空时以 stderr 作为错误信息；</li>
     *   <li>脚本返回成功后仍需 {@link Files#exists(Path)} 确认 PNG 文件确实落盘。</li>
     * </ul>
     *
     * @param inputFile     源 GeoTIFF 文件路径
     * @param thumbnailFile 输出的缩略图 PNG 文件路径
     * @throws IOException          如果进程 I/O 或 JSON 解析失败
     * @throws InterruptedException 如果当前线程被中断
     */
    private void runPythonThumbnail(Path inputFile, Path thumbnailFile) throws IOException, InterruptedException {
        // Java 侧只负责编排流程，影像波段读取和拉伸逻辑交给 Python/rasterio。
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                properties.getPythonExecutable(),
                properties.getThumbnailScript(),
                inputFile.toAbsolutePath().toString(),
                thumbnailFile.toAbsolutePath().toString()
        ));

        Process process = processBuilder.start();
        boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            // 超时后强制销毁，防止僵尸进程累积导致系统资源耗尽。
            process.destroyForcibly();
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 缩略图生成超时");
        }

        // 先读取全部输出再判断，避免子进程因管道缓冲区满而阻塞。
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (stdout.isBlank()) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "Python 缩略图脚本无输出：" + stderr);
        }

        // 即使脚本返回成功，也确认 PNG 文件确实落盘，避免上传空路径。
        JsonNode root = objectMapper.readTree(stdout);
        if (!root.path("success").asBoolean(false)) {
            throw new BusinessException(ResultCode.FAIL.getCode(), root.path("error").asText("缩略图生成失败"));
        }
        // 二次校验：脚本可能返回 success:true 但实际文件并未写入（如磁盘空间不足）
        if (!Files.exists(thumbnailFile)) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "缩略图脚本未生成 PNG 文件");
        }
    }

    /**
     * 安静删除文件或目录，忽略所有 I/O 异常。
     *
     * <p>用于 finally 块中的资源清理，避免清理异常覆盖主流程的业务异常。
     * 生产环境下废弃的临时文件可由操作系统 TMP 清理策略兜底。</p>
     *
     * @param path 待删除的路径，为 null 时直接返回
     */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响返回；生产环境可接入日志。
        }
    }
}