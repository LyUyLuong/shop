package com.lul.shop.outbox.domain;

import java.util.List;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findPublishableEvents(int limit, int maxRetryCount);
}