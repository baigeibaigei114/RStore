package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import org.springframework.amqp.core.Message;

public interface RsTaskDeadLetterService {

    void record(RemoteSensingTaskMessage taskMessage, Message rawMessage);
}
