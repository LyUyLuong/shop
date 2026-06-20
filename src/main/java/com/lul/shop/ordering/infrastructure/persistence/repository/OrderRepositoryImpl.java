package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import com.lul.shop.ordering.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderMapper orderMapper;

    public OrderRepositoryImpl(OrderJpaRepository orderJpaRepository,
                               OrderMapper orderMapper) {
        this.orderJpaRepository = orderJpaRepository;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public Order save(Order order) {
        OrderJpaEntity entity = orderMapper.toEntity(order);
        OrderJpaEntity savedEntity = orderJpaRepository.save(entity);

        return orderMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderJpaRepository.findById(orderId)
                .map(orderMapper::toDomain);
    }

    @Override
    public Optional<Order> findByIdAndUserId(UUID orderId, UUID userId) {
        return orderJpaRepository.findByIdAndUserId(orderId, userId)
                .map(orderMapper::toDomain);
    }

    @Override
    public List<Order> findByUserId(UUID userId) {
        return orderJpaRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(orderMapper::toDomain)
                .toList();
    }
}