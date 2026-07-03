package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderStatusHistoryJpaEntity;
import com.lul.shop.ordering.infrastructure.persistence.mapper.OrderStatusHistoryMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class OrderStatusHistoryRepositoryImpl implements OrderStatusHistoryRepository {

    private final OrderStatusHistoryJpaRepository orderStatusHistoryJpaRepository;
    private final OrderStatusHistoryMapper orderStatusHistoryMapper;

    public OrderStatusHistoryRepositoryImpl(OrderStatusHistoryJpaRepository orderStatusHistoryJpaRepository,
                                            OrderStatusHistoryMapper orderStatusHistoryMapper) {
        this.orderStatusHistoryJpaRepository = orderStatusHistoryJpaRepository;
        this.orderStatusHistoryMapper = orderStatusHistoryMapper;
    }

    @Override
    @Transactional
    public OrderStatusHistory save(OrderStatusHistory history) {
        Objects.requireNonNull(history, "history must not be null");

        OrderStatusHistoryJpaEntity entity = orderStatusHistoryMapper.toEntity(history);
        OrderStatusHistoryJpaEntity savedEntity = orderStatusHistoryJpaRepository.save(entity);

        return orderStatusHistoryMapper.toDomain(savedEntity);
    }

    @Override
    public List<OrderStatusHistory> findTimelineByOrderId(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        return orderStatusHistoryJpaRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream()
                .map(orderStatusHistoryMapper::toDomain)
                .toList();
    }
}