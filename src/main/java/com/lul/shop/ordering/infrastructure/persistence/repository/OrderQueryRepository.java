package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.CustomerOrderSummary;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.exception.CommonErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class OrderQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public PageResult<OrderSummary> searchSummaries(OrderSearchCriteria criteria, PageQuery pageQuery) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(pageQuery, "pageQuery must not be null");

        int page = pageQuery.page();
        int size = pageQuery.size();
        int offset = calculateOffset(page, size);

        Map<String, Object> params = new HashMap<>();
        String whereClause = buildWhereClause(criteria, params);

        String idJpql = """
                select o.id
                from OrderJpaEntity o
                """ + whereClause + """
                order by o.createdAt desc, o.id desc
                """;

        String countJpql = """
                select count(o)
                from OrderJpaEntity o
                """ + whereClause;

        TypedQuery<UUID> idQuery = entityManager.createQuery(idJpql, UUID.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);

        applyParams(idQuery, params);
        applyParams(countQuery, params);

        List<UUID> orderIds = idQuery
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();

        long totalElements = countQuery.getSingleResult();
        int totalPages = calculateTotalPages(totalElements, size);
        boolean hasNext = (long) page + 1 < totalPages;

        if (orderIds.isEmpty()) {
            return new PageResult<>(List.of(), page, size, totalElements, totalPages, hasNext);
        }

        List<OrderJpaEntity> orders = entityManager.createQuery("""
                        select o
                        from OrderJpaEntity o
                        where o.id in :orderIds
                        """, OrderJpaEntity.class)
                .setParameter("orderIds", orderIds)
                .getResultList();

        Map<UUID, OrderJpaEntity> ordersById = orders.stream()
                .collect(Collectors.toMap(OrderJpaEntity::getId, Function.identity()));

        Map<UUID, Integer> itemCounts = countItemsByOrderIds(orderIds);

        List<OrderSummary> content = orderIds.stream()
                .map(ordersById::get)
                .filter(Objects::nonNull)
                .map(order -> toSummary(order, itemCounts.getOrDefault(order.getId(), 0)))
                .toList();

        return new PageResult<>(content, page, size, totalElements, totalPages, hasNext);
    }

    public PageResult<CustomerOrderSummary>
    findCustomerSummariesByUserId(
            UUID userId,
            PageQuery pageQuery
    ) {
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        Objects.requireNonNull(
                pageQuery,
                "pageQuery must not be null"
        );

        int page = pageQuery.page();
        int size = pageQuery.size();
        int offset = calculateOffset(page, size);

        long totalElements = entityManager.createQuery("""
                    select count(orderEntity.id)
                    from OrderJpaEntity orderEntity
                    where orderEntity.userId = :userId
                    """, Long.class)
                .setParameter("userId", userId)
                .getSingleResult();

        int totalPages = calculateTotalPages(
                totalElements,
                size
        );

        if (totalElements == 0L || offset >= totalElements) {
            return new PageResult<>(
                    List.of(),
                    page,
                    size,
                    totalElements,
                    totalPages,
                    false
            );
        }

        List<UUID> orderIds = entityManager.createQuery("""
                    select orderEntity.id
                    from OrderJpaEntity orderEntity
                    where orderEntity.userId = :userId
                    order by
                        orderEntity.createdAt desc,
                        orderEntity.id desc
                    """, UUID.class)
                .setParameter("userId", userId)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();

        if (orderIds.isEmpty()) {
            return new PageResult<>(
                    List.of(),
                    page,
                    size,
                    totalElements,
                    totalPages,
                    false
            );
        }

        List<CustomerOrderSummary> content =
                entityManager.createQuery("""
                            select
                                orderEntity.id as orderId,
                                orderEntity.status as orderStatus,
                                orderEntity.paymentMode as paymentMode,
                                orderEntity.totalAmount as totalAmount,
                                count(orderItem.id) as itemCount,
                                orderEntity.createdAt as createdAt,
                                orderEntity.updatedAt as updatedAt
                            from OrderJpaEntity orderEntity
                            left join orderEntity.items orderItem
                            where orderEntity.userId = :userId
                              and orderEntity.id in :orderIds
                            group by
                                orderEntity.id,
                                orderEntity.status,
                                orderEntity.paymentMode,
                                orderEntity.totalAmount,
                                orderEntity.createdAt,
                                orderEntity.updatedAt
                            order by
                                orderEntity.createdAt desc,
                                orderEntity.id desc
                            """, Tuple.class)
                        .setParameter("userId", userId)
                        .setParameter("orderIds", orderIds)
                        .getResultStream()
                        .map(this::toCustomerOrderSummary)
                        .toList();

        return new PageResult<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page < totalPages - 1
        );
    }

    private CustomerOrderSummary toCustomerOrderSummary(
            Tuple row
    ) {
        OrderPaymentMode paymentMode =
                row.get(
                        "paymentMode",
                        OrderPaymentMode.class
                );

        return new CustomerOrderSummary(
                row.get("orderId", UUID.class),
                row.get("orderStatus", OrderStatus.class),
                paymentMode == null
                        ? OrderPaymentMode.MOCK
                        : paymentMode,
                row.get("totalAmount", BigDecimal.class),
                Math.toIntExact(
                        row.get("itemCount", Long.class)
                ),
                row.get("createdAt", Instant.class),
                row.get("updatedAt", Instant.class)
        );
    }

    private int calculateOffset(int page, int size) {
        long offset = (long) page * size;

        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_REQUEST,
                    "page offset is too large"
            );
        }

        return (int) offset;
    }

    private int calculateTotalPages(
            long totalElements,
            int size
    ) {
        if (totalElements == 0L) {
            return 0;
        }

        long totalPages =
                1L + (totalElements - 1L) / size;

        if (totalPages > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "totalPages exceeds supported range"
            );
        }

        return (int) totalPages;
    }

    private Map<UUID, Integer> countItemsByOrderIds(List<UUID> orderIds) {
        List<Object[]> rows = entityManager.createQuery("""
                        select i.order.id, count(i.id)
                        from OrderItemJpaEntity i
                        where i.order.id in :orderIds
                        group by i.order.id
                        """, Object[].class)
                .setParameter("orderIds", orderIds)
                .getResultList();

        return rows.stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> ((Long) row[1]).intValue()
                ));
    }

    private OrderSummary toSummary(OrderJpaEntity order, int itemCount) {
        return new OrderSummary(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                itemCount,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private String buildWhereClause(OrderSearchCriteria criteria, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("where 1 = 1 ");

        if (criteria.status() != null) {
            where.append("and o.status = :status ");
            params.put("status", criteria.status());
        }

        if (criteria.createdFrom() != null) {
            where.append("and o.createdAt >= :createdFrom ");
            params.put("createdFrom", criteria.createdFrom());
        }

        if (criteria.createdTo() != null) {
            where.append("and o.createdAt <= :createdTo ");
            params.put("createdTo", criteria.createdTo());
        }

        return where.toString();
    }

    private void applyParams(TypedQuery<?> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }
}