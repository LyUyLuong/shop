package com.lul.shop.ordering.domain;

import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(UUID orderId);

    Optional<Order> findByIdAndUserId(UUID orderId, UUID userId);

    List<Order> findByUserId(UUID userId);

    PageResult<OrderSummary> searchSummaries(OrderSearchCriteria criteria, PageQuery pageQuery);
}