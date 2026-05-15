package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.service.MessageOutboxService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class MessageOutboxRetryScheduler {

    private final MessageOutboxService messageOutboxService;

    public MessageOutboxRetryScheduler(MessageOutboxService messageOutboxService) {
        this.messageOutboxService = messageOutboxService;
    }

    @Scheduled(fixedDelayString = "${rabbitmq.remote-sensing-task.outbox-retry-delay-ms:30000}")
    public void retryPendingMessages() {
        messageOutboxService.publishDueMessages();
    }
}
