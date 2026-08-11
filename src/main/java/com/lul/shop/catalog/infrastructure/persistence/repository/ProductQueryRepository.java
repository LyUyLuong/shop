package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductSearchSlice;
import com.lul.shop.catalog.domain.ProductSearchWindow;
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

    private static final String MATCH_PRIORITY_EXPRESSION = """
            CASE
                WHEN lower(p.sku) = lower(:exactKeyword)
                    THEN 0
                WHEN lower(p.name)
                     LIKE lower(:namePrefixPattern)
                     ESCAPE :likeEscapeCharacter
                    THEN 1
                ELSE 2
            END
            """.strip();

    @PersistenceContext
    private EntityManager entityManager;

    public PageResult<ProductJpaEntity> search(
            ProductSearchCriteria criteria,
            PageQuery pageQuery
    ) {
        Objects.requireNonNull(
                criteria,
                "criteria must not be null"
        );
        Objects.requireNonNull(
                pageQuery,
                "pageQuery must not be null"
        );

        int page = pageQuery.page();
        int size = pageQuery.size();
        int jpaOffset = toJpaOffset(pageQuery);

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
                .setFirstResult(jpaOffset)
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

    public ProductSearchSlice<ProductJpaEntity> search(
            ProductSearchCriteria criteria,
            ProductSearchWindow window
    ) {
        Objects.requireNonNull(
                criteria,
                "criteria must not be null"
        );
        Objects.requireNonNull(
                window,
                "window must not be null"
        );

        if (criteria.keyword() == null) {
            return searchBrowseSlice(
                    criteria,
                    window
            );
        }

        return searchRankedSlice(
                criteria,
                window
        );
    }

    private ProductSearchSlice<ProductJpaEntity>
    searchBrowseSlice(
            ProductSearchCriteria criteria,
            ProductSearchWindow window
    ) {
        Map<String, Object> params = new HashMap<>();

        String whereClause = buildWhereClause(
                criteria,
                params
        );
        String continuationClause =
                buildBrowseContinuationClause(
                        window,
                        params
                );

        String dataJpql = """
                SELECT p
                FROM ProductJpaEntity p
                """ + whereClause
                + continuationClause
                + buildBrowseOrderByClause();

        TypedQuery<ProductJpaEntity> dataQuery =
                entityManager.createQuery(
                        dataJpql,
                        ProductJpaEntity.class
                );

        applyParams(dataQuery, params);

        List<ProductJpaEntity> rows = dataQuery
                .setMaxResults(toProbeLimit(window.size()))
                .getResultList();

        return toBrowseSlice(
                rows,
                window.size()
        );
    }

    private ProductSearchSlice<ProductJpaEntity>
    searchRankedSlice(
            ProductSearchCriteria criteria,
            ProductSearchWindow window
    ) {
        Map<String, Object> params = new HashMap<>();

        String whereClause = buildWhereClause(
                criteria,
                params
        );

        addRankingParameters(
                criteria,
                params
        );

        String continuationClause =
                buildRankedContinuationClause(
                        window,
                        params
                );

        String dataJpql = """
                SELECT p, %s
                FROM ProductJpaEntity p
                """.formatted(MATCH_PRIORITY_EXPRESSION)
                + whereClause
                + continuationClause
                + buildRankedOrderByClause();

        TypedQuery<Object[]> dataQuery =
                entityManager.createQuery(
                        dataJpql,
                        Object[].class
                );

        applyParams(dataQuery, params);

        List<Object[]> rows = dataQuery
                .setMaxResults(toProbeLimit(window.size()))
                .getResultList();

        return toRankedSlice(
                rows,
                window.size()
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
            return buildBrowseOrderByClause();
        }

        addRankingParameters(
                criteria,
                params
        );

        return buildRankedOrderByClause();
    }

    private String buildBrowseOrderByClause() {
        return """
                ORDER BY
                    p.createdAt DESC,
                    p.id DESC
                """;
    }

    private String buildRankedOrderByClause() {
        return """
                ORDER BY
                    %s ASC,
                    p.createdAt DESC,
                    p.id DESC
                """.formatted(MATCH_PRIORITY_EXPRESSION);
    }

    private String buildBrowseContinuationClause(
            ProductSearchWindow window,
            Map<String, Object> params
    ) {
        if (window.isFirst()) {
            return "";
        }

        ProductSearchPosition position =
                window.afterPosition().orElseThrow();

        if (position.hasMatchPriority()) {
            throw new IllegalArgumentException(
                    "browse position must not contain matchPriority"
            );
        }

        params.put(
                "afterCreatedAt",
                position.createdAt()
        );
        params.put(
                "afterId",
                position.id()
        );

        return """
                AND (
                    p.createdAt < :afterCreatedAt
                    OR (
                        p.createdAt = :afterCreatedAt
                        AND p.id < :afterId
                    )
                )
                """;
    }

    private String buildRankedContinuationClause(
            ProductSearchWindow window,
            Map<String, Object> params
    ) {
        if (window.isFirst()) {
            return "";
        }

        ProductSearchPosition position =
                window.afterPosition().orElseThrow();

        if (!position.hasMatchPriority()) {
            throw new IllegalArgumentException(
                    "ranked position requires matchPriority"
            );
        }

        params.put(
                "afterMatchPriority",
                position.requiredMatchPriority()
        );
        params.put(
                "afterCreatedAt",
                position.createdAt()
        );
        params.put(
                "afterId",
                position.id()
        );

        return """
                AND (
                    %1$s > :afterMatchPriority
                    OR (
                        %1$s = :afterMatchPriority
                        AND (
                            p.createdAt < :afterCreatedAt
                            OR (
                                p.createdAt = :afterCreatedAt
                                AND p.id < :afterId
                            )
                        )
                    )
                )
                """.formatted(MATCH_PRIORITY_EXPRESSION);
    }

    private void addRankingParameters(
            ProductSearchCriteria criteria,
            Map<String, Object> params
    ) {
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
    }

    private ProductSearchSlice<ProductJpaEntity>
    toBrowseSlice(
            List<ProductJpaEntity> rows,
            int requestedSize
    ) {
        boolean hasNext = rows.size() > requestedSize;

        List<ProductJpaEntity> content = hasNext
                ? rows.subList(0, requestedSize)
                : rows;

        if (!hasNext) {
            return ProductSearchSlice.terminal(content);
        }

        ProductJpaEntity lastReturned =
                content.get(content.size() - 1);

        return ProductSearchSlice.continued(
                content,
                ProductSearchPosition.browse(
                        lastReturned.getCreatedAt(),
                        lastReturned.getId()
                )
        );
    }

    private ProductSearchSlice<ProductJpaEntity>
    toRankedSlice(
            List<Object[]> rows,
            int requestedSize
    ) {
        boolean hasNext = rows.size() > requestedSize;
        int contentSize = Math.min(
                rows.size(),
                requestedSize
        );

        List<ProductJpaEntity> content = rows
                .subList(0, contentSize)
                .stream()
                .map(this::readRankedProduct)
                .toList();

        if (!hasNext) {
            return ProductSearchSlice.terminal(content);
        }

        Object[] lastReturnedRow =
                rows.get(contentSize - 1);

        ProductJpaEntity lastReturned =
                readRankedProduct(lastReturnedRow);

        int matchPriority =
                readMatchPriority(lastReturnedRow);

        return ProductSearchSlice.continued(
                content,
                ProductSearchPosition.ranked(
                        matchPriority,
                        lastReturned.getCreatedAt(),
                        lastReturned.getId()
                )
        );
    }

    private ProductJpaEntity readRankedProduct(
            Object[] row
    ) {
        if (
                row == null
                        || row.length != 2
                        || !(row[0] instanceof ProductJpaEntity product)
        ) {
            throw new IllegalStateException(
                    "ranked product projection has an invalid shape"
            );
        }

        return product;
    }

    private int readMatchPriority(Object[] row) {
        if (
                row == null
                        || row.length != 2
                        || !(row[1] instanceof Number priority)
        ) {
            throw new IllegalStateException(
                    "ranked priority projection has an invalid shape"
            );
        }

        return priority.intValue();
    }

    private int toProbeLimit(int requestedSize) {
        try {
            return Math.addExact(requestedSize, 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "size is too large to request a probe row",
                    exception
            );
        }
    }

    private int toJpaOffset(PageQuery pageQuery) {
        long offset =
                (long) pageQuery.page() * pageQuery.size();

        try {
            return Math.toIntExact(offset);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "page offset exceeds JPA's supported integer range",
                    exception
            );
        }
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