package com.lul.shop.catalog.infrastructure.persistence.entity;

import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.persistence.UpdatableJpaEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ProductJpaEntity extends UpdatableJpaEntity {

    @Column(name = "sku", nullable = false, length = 100)
    private String sku;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;
}