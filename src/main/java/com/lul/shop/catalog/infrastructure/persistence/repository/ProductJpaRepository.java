package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProductJpaEntity p
            set p.stockQuantity = p.stockQuantity - :quantity
            where p.id = :productId
              and p.stockQuantity >= :quantity
              and p.status = :activeStatus
            """)
    int decreaseStockIfEnough(@Param("productId") UUID productId,
                              @Param("quantity") int quantity,
                              @Param("activeStatus") ProductStatus activeStatus);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update ProductJpaEntity p
        set p.stockQuantity = p.stockQuantity + :quantity
        where p.id = :productId
        """)
    int increaseStock(
            @Param("productId") UUID productId,
            @Param("quantity") int quantity
    );
}