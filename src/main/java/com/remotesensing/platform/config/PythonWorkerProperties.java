package com.remotesensing.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "python-worker")
public class PythonWorkerProperties {

    private String pythonExecutable = "python";
    private String parseMetadataScript = "python-worker/scripts/parse_metadata.py";
    private String thumbnailScript = "python-worker/scripts/generate_thumbnail.py";
    private Integer timeoutSeconds = 60;
}
