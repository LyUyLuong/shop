package com.lul.shop.ordering.domain;

import java.util.List;
import java.util.UUID;

public interface OrderStatusHistoryRepository {

    OrderStatusHistory save(OrderStatusHistory history);

    List<OrderStatusHistory> findTimelineByOrderId(UUID orderId);
}