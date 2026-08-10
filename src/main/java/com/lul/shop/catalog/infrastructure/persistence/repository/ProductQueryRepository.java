package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
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

@Repository
@Transactional(readOnly = true)
public class ProductQueryRepository {

    private static final char LIKE_ESCAPE_CHARACTER = '\\';

    @PersistenceContext
    private EntityManager entityManager;

    public PageResult<ProductJpaEntity> search(
            ProductSearchCriteria criteria,
            PageQuery pageQuery
    ) {
        int page = pageQuery.page();
        int size = pageQuery.size();

        Map<String, Object> filterParams = new HashMap<>();
        String whereClause = buildWhereClause(
                criteria,
                filterParams
        );

        Map<String, Object> dataParams =
                new HashMap<>(filterParams);
        String orderByClause = buildOrderByClause(
                criteria,
                dataParams
        );

        String dataJpql = """
                SELECT p
                FROM ProductJpaEntity p
                """ + whereClause + orderByClause;

        String countJpql = """
                SELECT count(p)
                FROM ProductJpaEntity p
                """ + whereClause;

        TypedQuery<ProductJpaEntity> dataQuery =
                entityManager.createQuery(
                        dataJpql,
                        ProductJpaEntity.class
                );

        TypedQuery<Long> countQuery =
                entityManager.createQuery(
                        countJpql,
                        Long.class
                );

        applyParams(dataQuery, dataParams);
        applyParams(countQuery, filterParams);

        List<ProductJpaEntity> content = dataQuery
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        long totalElements = countQuery.getSingleResult();
        int totalPages = (int) Math.ceil(
                (double) totalElements / size
        );
        boolean hasNext = page + 1 < totalPages;

        return new PageResult<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                hasNext
        );
    }

    private String buildWhereClause(
            ProductSearchCriteria criteria,
            Map<String, Object> params
    ) {
        StringBuilder where =
                new StringBuilder("WHERE 1 = 1\n");

        if (criteria.keyword() != null) {
            where.append("""
                    AND (
                        lower(p.sku) LIKE lower(:keywordPattern)
                        OR lower(p.name) LIKE lower(:keywordPattern)
                    )
                    """);

            params.put(
                    "keywordPattern",
                    "%" + criteria.keyword() + "%"
            );
        }

        if (criteria.status() != null) {
            where.append("AND p.status = :status\n");
            params.put("status", criteria.status());
        }

        if (criteria.minPrice() != null) {
            where.append("AND p.price >= :minPrice\n");
            params.put("minPrice", criteria.minPrice());
        }

        if (criteria.maxPrice() != null) {
            where.append("AND p.price <= :maxPrice\n");
            params.put("maxPrice", criteria.maxPrice());
        }

        return where.toString();
    }

    private String buildOrderByClause(
            ProductSearchCriteria criteria,
            Map<String, Object> params
    ) {
        if (criteria.keyword() == null) {
            return "ORDER BY p.createdAt DESC, p.id DESC\n";
        }

        params.put(
                "exactKeyword",
                criteria.keyword()
        );
        params.put(
                "namePrefixPattern",
                escapeLikePattern(criteria.keyword()) + "%"
        );
        params.put(
                "likeEscapeCharacter",
                LIKE_ESCAPE_CHARACTER
        );

        return """
                ORDER BY
                    CASE
                        WHEN lower(p.sku) = lower(:exactKeyword)
                            THEN 0
                        WHEN lower(p.name)
                             LIKE lower(:namePrefixPattern)
                             ESCAPE :likeEscapeCharacter
                            THEN 1
                        ELSE 2
                    END ASC,
                    p.createdAt DESC,
                    p.id DESC
                """;
    }

    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void applyParams(
            TypedQuery<?> query,
            Map<String, Object> params
    ) {
        params.forEach(query::setParameter);
    }
}