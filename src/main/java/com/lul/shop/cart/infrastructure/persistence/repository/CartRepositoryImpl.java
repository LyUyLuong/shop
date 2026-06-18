package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import com.lul.shop.cart.infrastructure.persistence.mapper.CartMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class CartRepositoryImpl implements CartRepository {

    private final CartJpaRepository cartJpaRepository;
    private final CartMapper cartMapper;

    public CartRepositoryImpl(CartJpaRepository cartJpaRepository,
                              CartMapper cartMapper) {
        this.cartJpaRepository = cartJpaRepository;
        this.cartMapper = cartMapper;
    }

    @Override
    public Optional<Cart> findByUserId(UUID userId) {
        return cartJpaRepository.findByUserId(userId)
                .map(cartMapper::toDomain);
    }

    @Override
    @Transactional
    public Cart save(Cart cart) {
        CartJpaEntity entity = cartMapper.toEntity(cart);
        CartJpaEntity savedEntity = cartJpaRepository.save(entity);

        return cartMapper.toDomain(savedEntity);
    }
}