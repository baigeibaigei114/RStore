package com.remotesensing.platform.service;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

public interface RabbitPublishFailureService {

    void handleConfirm(CorrelationData correlationData, boolean ack, String cause);

    void handleReturn(ReturnedMessage returnedMessage);
}
