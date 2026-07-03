package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

        Map<String, Object> params = new HashMap<>();
        String whereClause = buildWhereClause(criteria, params);

        String idJpql = """
                select o.id
                from OrderJpaEntity o
                """ + whereClause + """
                order by o.createdAt desc
                """;

        String countJpql = """
                select count(o)
                from OrderJpaEntity o
                """ + whereClause;

        TypedQuery<UUID> idQuery = entityManager.createQuery(idJpql, UUID.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);

        applyParams(idQuery, params);
        applyParams(countQuery, params);

        int page = pageQuery.page();
        int size = pageQuery.size();

        List<UUID> orderIds = idQuery
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        long totalElements = countQuery.getSingleResult();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        boolean hasNext = page + 1 < totalPages;

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