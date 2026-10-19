package com.remotesensing.platform.agent;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class AgentRecovery {
    private final AgentService service;
    private final AgentProperties properties;
    public AgentRecovery(AgentService service, AgentProperties properties) { this.service = service; this.properties = properties; }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!properties.isEnabled()) return;
        try { service.recoverExpired(); }
        catch (DataAccessException error) {
            properties.setEnabled(false);
            org.slf4j.LoggerFactory.getLogger(AgentRecovery.class).warn("Agent recovery unavailable; generation disabled errorType={}", error.getClass().getSimpleName());
        }
    }
}
