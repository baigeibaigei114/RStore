package com.remotesensing.platform.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {
    private boolean enabled = false;
    private String baseUrl = "http://agent-service:8000";
    private String internalToken = "";
    private int connectSeconds = 3;
    private int requestSeconds = 35;
    private int roundSeconds = 30;
    private int concurrentCalls = 2;
    private int ownerRateLimit = 10;
    private int ownerRateSeconds = 60;

    @Override public String toString() { return "AgentProperties[enabled=" + enabled + "]"; }
}
