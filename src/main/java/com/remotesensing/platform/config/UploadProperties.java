package com.remotesensing.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /**
     * 大文件上传会同时占用磁盘 IO、Web 线程和 Python 进程，本地开发默认保守限制。
     */
    private int maxConcurrent = 2;
}
