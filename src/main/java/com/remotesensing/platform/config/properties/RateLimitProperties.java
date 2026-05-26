package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口限流配置。
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private int loginIpLimit = 10;
    private long loginIpWindowSeconds = 60;

    private int loginUsernameLimit = 5;
    private long loginUsernameWindowSeconds = 60;

    private int taskSubmitLimit = 10;
    private long taskSubmitWindowSeconds = 60;

    private int presignedUrlLimit = 60;
    private long presignedUrlWindowSeconds = 60;

    private int uploadLimit = 5;
    private long uploadWindowSeconds = 300;

    private int geoserverPublishLimit = 3;
    private long geoserverPublishWindowSeconds = 300;

    private int aiQueryLimit = 20;
    private long aiQueryWindowSeconds = 60;

    private int aiReportLimit = 3;
    private long aiReportWindowSeconds = 300;
}
