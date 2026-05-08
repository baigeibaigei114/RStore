package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.PythonWorkerProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.GeoTiffThumbnailService;
import com.remotesensing.platform.service.MinioService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GeoTiffThumbnailServiceImpl implements GeoTiffThumbnailService {

    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
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

    @Override
    public String generateAndUpload(MultipartFile file, Long imageId) {
        Path tempDir = null;
        Path inputFile = null;
        Path thumbnailFile = null;
        try {
            tempDir = Files.createTempDirectory("rs-thumb-");
            inputFile = tempDir.resolve(buildSafeTempFilename(file.getOriginalFilename()));
            thumbnailFile = tempDir.resolve(imageId + ".png");
            Files.copy(file.getInputStream(), inputFile, StandardCopyOption.REPLACE_EXISTING);

            runPythonThumbnail(inputFile, thumbnailFile);
            String objectKey = buildThumbnailObjectKey(imageId);
            minioService.uploadLocalFile(thumbnailFile, objectKey, PNG_CONTENT_TYPE);
            return objectKey;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 缩略图生成失败：" + exception.getMessage());
        } finally {
            deleteQuietly(thumbnailFile);
            deleteQuietly(inputFile);
            deleteQuietly(tempDir);
        }
    }

    private void runPythonThumbnail(Path inputFile, Path thumbnailFile) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                properties.getPythonExecutable(),
                properties.getThumbnailScript(),
                inputFile.toAbsolutePath().toString(),
                thumbnailFile.toAbsolutePath().toString()
        ));

        Process process = processBuilder.start();
        boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 缩略图生成超时");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (stdout.isBlank()) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "Python 缩略图脚本无输出：" + stderr);
        }

        JsonNode root = objectMapper.readTree(stdout);
        if (!root.path("success").asBoolean(false)) {
            throw new BusinessException(ResultCode.FAIL.getCode(), root.path("error").asText("缩略图生成失败"));
        }
        if (!Files.exists(thumbnailFile)) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "缩略图脚本未生成 PNG 文件");
        }
    }

    private String buildThumbnailObjectKey(Long imageId) {
        LocalDate now = LocalDate.now();
        return "thumbnail/%s/%s/%s.png".formatted(
                YEAR_FORMATTER.format(now),
                MONTH_FORMATTER.format(now),
                imageId
        );
    }

    private String buildSafeTempFilename(String originalFilename) {
        String filename = originalFilename == null || originalFilename.isBlank() ? "upload.tif" : originalFilename;
        String safeFilename = filename
                .replace("\\", "_")
                .replace("/", "_")
                .replaceAll("\\s+", "_");
        return UUID.randomUUID() + "_" + safeFilename;
    }

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
