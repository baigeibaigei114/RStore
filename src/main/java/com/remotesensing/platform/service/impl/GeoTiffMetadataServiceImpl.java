package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.PythonWorkerProperties;
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

/**
 * GeoTIFF 元数据解析服务实现类。
 *
 * <p>职责：调用 Python 工作进程（独立进程）解析 GeoTIFF 文件的元数据信息，将脚本输出的
 * 结构化 JSON 反序列化为 {@link GeoTiffMetadataVO} 返回。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>Java 侧只负责进程编排（启动、超时控制、输出读取），不参与影像格式解析本身；</li>
 *   <li>Python 脚本通过 stdout 输出 JSON，stderr 仅用于诊断异常；</li>
 *   <li>超时机制防止 Python 进程长时间阻塞 Java 服务线程；</li>
 *   <li>独立进程模型便于后续替换为容器化处理节点（如 Kubernetes Job）。</li>
 * </ul>
 */
@Service
public class GeoTiffMetadataServiceImpl implements GeoTiffMetadataService {

    private final PythonWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public GeoTiffMetadataServiceImpl(PythonWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析指定 GeoTIFF 文件的元数据。
     *
     * <p>将元数据解析异常统一包装为 {@link BusinessException}，调用方无需关心底层进程调用细节。</p>
     *
     * @param filePath GeoTIFF 文件的绝对路径
     * @return 解析后的元数据视图对象
     * @throws BusinessException 如果解析超时、脚本无输出或返回失败标志时抛出
     */
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

    /**
     * 启动 Python 进程执行元数据解析脚本，并读取其结果。
     *
     * <p>关键约束：</p>
     * <ul>
     *   <li>必须在 {@code properties.getTimeoutSeconds()} 内完成，否则强行终止进程并抛出超时异常；</li>
     *   <li>脚本 stdout 必须输出合法 JSON，格式为 {@code {"success": true/false, "data": {...}, "error": "..."}}；</li>
     *   <li>{@code success} 为 {@code false} 时作为业务异常抛出。</li>
     * </ul>
     *
     * @param tempFile 待解析的 GeoTIFF 文件路径
     * @return 解析后的元数据视图对象
     * @throws IOException          如果进程 I/O 或 JSON 解析失败
     * @throws InterruptedException 如果当前线程被中断
     */
    private GeoTiffMetadataVO runPythonParser(Path tempFile) throws IOException, InterruptedException {
        // Python worker 作为独立进程运行，便于后续替换为容器化处理节点。
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                properties.getPythonExecutable(),
                properties.getParseMetadataScript(),
                tempFile.toAbsolutePath().toString()
        ));

        Process process = processBuilder.start();
        // waitFor 返回 false 表示超时，不进入 interrupted 分支，需要单独处理。
        boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            // 超时后必须强制销毁子进程，否则僵尸进程会堆积并耗尽系统资源。
            process.destroyForcibly();
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据解析超时，超过 "
                    + Duration.ofSeconds(properties.getTimeoutSeconds()).toSeconds() + " 秒");
        }

        // 脚本约定 stdout 输出结构化 JSON，stderr 仅作为异常诊断信息。
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (stdout.isBlank()) {
            // 无 stdout 意味着脚本异常退出或输出被重定向，此时将 stderr 作为错误信息返回。
            throw new BusinessException(ResultCode.FAIL.getCode(), "Python 解析脚本无输出：" + stderr);
        }

        // 解析 JSON 响应，按约定校验 success 字段。
        JsonNode root = objectMapper.readTree(stdout);
        if (!root.path("success").asBoolean(false)) {
            String error = root.path("error").asText("未知错误");
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), error);
        }

        // 将 data 节点直接映射为 VO，JSON 字段名与 VO 属性名一致。
        return objectMapper.treeToValue(root.path("data"), GeoTiffMetadataVO.class);
    }
}