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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class GeoTiffMetadataServiceImpl implements GeoTiffMetadataService {

    private final PythonWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public GeoTiffMetadataServiceImpl(PythonWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeoTiffMetadataVO parse(Path filePath) {
        try {
            return runPythonParser(filePath);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据解析失败：" + exception.getMessage());
        }
    }

    private GeoTiffMetadataVO runPythonParser(Path tempFile) throws IOException, InterruptedException {
        // Python worker 作为独立进程运行，便于后续替换为容器化处理节点。
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

        // 脚本约定 stdout 输出结构化 JSON，stderr 仅作为异常诊断信息。
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
}
