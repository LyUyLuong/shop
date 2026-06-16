package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    @Query("""
            select p
            from ProductJpaEntity p
            where lower(p.sku) = lower(:sku)
            """)
    Optional<ProductJpaEntity> findBySku(@Param("sku") String sku);

    @Query("""
            select count(p) > 0
            from ProductJpaEntity p
            where lower(p.sku) = lower(:sku)
            """)
    boolean existsBySku(@Param("sku") String sku);

    @Query("""
            select count(p) > 0
            from ProductJpaEntity p
            where lower(p.sku) = lower(:sku)
              and p.id <> :currentProductId
            """)
    boolean existsOtherProductWithSku(@Param("sku") String sku,
                                      @Param("currentProductId") UUID currentProductId);
}