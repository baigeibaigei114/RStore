package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.PythonWorkerProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.GeoTiffMetadataService;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GeoTiffMetadataServiceImpl implements GeoTiffMetadataService {

    private final PythonWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public GeoTiffMetadataServiceImpl(PythonWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeoTiffMetadataVO parse(MultipartFile file) {
        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("rs-geotiff-");
            tempFile = tempDir.resolve(buildSafeTempFilename(file.getOriginalFilename()));
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return runPythonParser(tempFile);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据解析失败：" + exception.getMessage());
        } finally {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
        }
    }

    private GeoTiffMetadataVO runPythonParser(Path tempFile) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                properties.getPythonExecutable(),
                properties.getParseMetadataScript(),
                tempFile.toAbsolutePath().toString()
        ));

        Process process = processBuilder.start();
        boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据解析超时，超过 "
                    + Duration.ofSeconds(properties.getTimeoutSeconds()).toSeconds() + " 秒");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (stdout.isBlank()) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "Python 解析脚本无输出：" + stderr);
        }

        JsonNode root = objectMapper.readTree(stdout);
        if (!root.path("success").asBoolean(false)) {
            String error = root.path("error").asText("未知错误");
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), error);
        }

        return objectMapper.treeToValue(root.path("data"), GeoTiffMetadataVO.class);
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
            // 临时文件清理失败不影响主流程，后续可接入日志记录。
        }
    }
}
