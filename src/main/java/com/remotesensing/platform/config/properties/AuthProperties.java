package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String jwtSecret = "dev-secret-change-me";
    private long tokenExpireMinutes = 1440;
    private boolean devUserHeaderEnabled = false;
    private String defaultUserId = "dev-user";
    private String workerToken = "dev-worker-token-change-me";
}
