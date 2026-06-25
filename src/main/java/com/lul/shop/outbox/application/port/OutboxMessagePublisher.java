package com.lul.shop.outbox.application.port;

import com.lul.shop.outbox.domain.OutboxEvent;

public interface OutboxMessagePublisher {

    void publish(OutboxEvent event);
}