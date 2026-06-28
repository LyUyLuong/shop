package com.lul.shop.notification.domain;

import java.util.Optional;
import java.util.UUID;

public interface NotificationEventLogRepository {

    NotificationEventLog save(NotificationEventLog eventLog);

    boolean existsByEventId(UUID eventId);

    Optional<NotificationEventLog> findByEventId(UUID eventId);
}