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

    @PersistenceContext
    private EntityManager entityManager;

    public PageResult<ProductJpaEntity> search(ProductSearchCriteria criteria, PageQuery pageQuery) {
        Map<String, Object> params = new HashMap<>();
        String whereClause = buildWhereClause(criteria, params);

        String dataJpql = """
                SELECT p
                FROM ProductJpaEntity p
                """ + whereClause  + """
                ORDER BY p.createdAt DESC
                """;

        String countJpql = """
                select count(p)
                from ProductJpaEntity p
                """ + whereClause;

        TypedQuery<ProductJpaEntity> dataQuery = entityManager.createQuery(dataJpql,ProductJpaEntity.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql,Long.class);

        applyParams(dataQuery, params);
        applyParams(countQuery, params);

        int page = pageQuery.page();
        int size = pageQuery.size();


        List<ProductJpaEntity> content = dataQuery
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        long totalElements = countQuery.getSingleResult();
        int totalPages = (int) Math.ceil((double) totalElements/size);
        boolean hasNext = page + 1 < totalPages;

        return  new PageResult<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                hasNext
        );

    }



    private String buildWhereClause(ProductSearchCriteria criteria, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("where 1 = 1 ");

        if(criteria.keyword() != null) {
            where.append("""
                    AND (
                        lower(p.sku) like :keyword
                        or lower(p.name) like :keyword
                    )
                    """);
            params.put("keyword", "%" + criteria.keyword().toLowerCase() + "%");
        }

        if(criteria.status() != null) {
            where.append(" and p.status = :status ");
            params.put("status", criteria.status());
        }

        if (criteria.minPrice() != null) {
            where.append(" and p.price >= :minPrice ");
            params.put("minPrice", criteria.minPrice());
        }

        if (criteria.maxPrice() != null) {
            where.append(" and p.price <= :maxPrice ");
            params.put("maxPrice", criteria.maxPrice());
        }

        return where.toString();

    }

    private void applyParams(TypedQuery<?> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

}
