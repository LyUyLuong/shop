package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import com.lul.shop.ordering.infrastructure.persistence.mapper.OrderMapper;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Repository
@Transactional(readOnly = true)
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final OrderMapper orderMapper;
    private final EntityManager entityManager;

    public OrderRepositoryImpl(
            OrderJpaRepository orderJpaRepository,
            OrderQueryRepository orderQueryRepository,
            OrderMapper orderMapper,
            EntityManager entityManager
    ) {
        this.orderJpaRepository = orderJpaRepository;
        this.orderQueryRepository = orderQueryRepository;
        this.orderMapper = orderMapper;
        this.entityManager = entityManager;
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
    public Optional<Order> findByIdAndUserId(
            UUID orderId,
            UUID userId
    ) {
        return orderJpaRepository
                .findByIdAndUserId(orderId, userId)
                .map(orderMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public Optional<Order> findByIdForUpdate(UUID orderId) {
        return orderJpaRepository.lockById(orderId)
                .map(this::detachLockedOrder)
                .flatMap(orderJpaRepository::findAggregateById)
                .map(orderMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public Optional<Order> findByIdAndUserIdForUpdate(
            UUID orderId,
            UUID userId
    ) {
        return orderJpaRepository
                .lockByIdAndUserId(orderId, userId)
                .map(this::detachLockedOrder)
                .flatMap(lockedOrderId ->
                        orderJpaRepository.findAggregateByIdAndUserId(
                                lockedOrderId,
                                userId
                        )
                )
                .map(orderMapper::toDomain);
    }

    private UUID detachLockedOrder(OrderJpaEntity lockedOrder) {
        UUID orderId = lockedOrder.getId();
        entityManager.detach(lockedOrder);
        return orderId;
    }

    @Override
    public List<Order> findByUserId(UUID userId) {
        return orderJpaRepository
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(orderMapper::toDomain)
                .toList();
    }

    @Override
    public PageResult<OrderSummary> searchSummaries(
            OrderSearchCriteria criteria,
            PageQuery pageQuery
    ) {
        return orderQueryRepository.searchSummaries(
                criteria,
                pageQuery
        );
    }

    @Override
    @Transactional(
            propagation = Propagation.MANDATORY,
            readOnly = false
    )
    public List<Order> claimExpiredForUpdate(
            Instant cutoff,
            int limit
    ) {
        Objects.requireNonNull(
                cutoff,
                "cutoff must not be null"
        );

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit must be greater than 0"
            );
        }

        List<UUID> claimedOrderIds =
                orderJpaRepository
                        .claimExpiredOrderIdsForUpdate(
                                cutoff,
                                limit
                        );

        List<Order> claimedOrders =
                new ArrayList<>(claimedOrderIds.size());

        for (UUID orderId : claimedOrderIds) {
            OrderJpaEntity entity = orderJpaRepository
                    .findAggregateById(orderId)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "claimed order disappeared: "
                                            + orderId
                            )
                    );

            claimedOrders.add(
                    orderMapper.toDomain(entity)
            );
        }

        return List.copyOf(claimedOrders);
    }
}