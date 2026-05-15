package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;

public interface MessageOutboxService {

    Long createTaskMessage(Long taskId, RemoteSensingTaskMessage message);

    void publishById(Long outboxId);

    void publishDueMessages();
}
