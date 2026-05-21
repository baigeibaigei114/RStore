package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python Worker 进程调用配置属性，前缀为 "python-worker"。
 * <p>
 * Python 脚本路径保持可配置，方便后续把 worker 拆成独立服务或容器。
 */
@Data
@ConfigurationProperties(prefix = "python-worker")
public class PythonWorkerProperties {

    /** Python 可执行文件路径，默认使用系统 PATH 中的 python。 */
    private String pythonExecutable = "python";

    /** 元数据解析脚本路径，用于提取 GeoTIFF 的空间参考、波段、分辨率等信息。 */
    private String parseMetadataScript = "python-worker/scripts/parse_metadata.py";

    /** 缩略图生成脚本路径，用于读取 GeoTIFF 并生成预览 PNG。 */
    private String thumbnailScript = "python-worker/scripts/generate_thumbnail.py";

    /** Python 进程执行超时秒数，默认 60 秒，超时后将终止进程。 */
    private Integer timeoutSeconds = 60;
}
