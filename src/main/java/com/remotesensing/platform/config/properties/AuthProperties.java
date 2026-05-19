package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String jwtSecret = "dev-secret-change-me";
    private long tokenExpireMinutes = 1440;
}
