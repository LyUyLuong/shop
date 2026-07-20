package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import com.lul.shop.cart.infrastructure.persistence.mapper.CartMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class CartRepositoryImpl implements CartRepository {

    private final CartJpaRepository cartJpaRepository;
    private final CartMapper cartMapper;
    private final Clock clock;

    public CartRepositoryImpl(
            CartJpaRepository cartJpaRepository,
            CartMapper cartMapper,
            Clock clock
    ) {
        this.cartJpaRepository = cartJpaRepository;
        this.cartMapper = cartMapper;
        this.clock = clock;
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

        entity.setUpdatedAt(nextMutationTime(cart));

        CartJpaEntity savedEntity =
                cartJpaRepository.saveAndFlush(entity);

        return cartMapper.toDomain(savedEntity);
    }

    private Instant nextMutationTime(Cart cart) {
        Instant now = clock.instant();
        Instant current = cart.getUpdatedAt();

        if (current == null || now.isAfter(current)) {
            return now;
        }

        return current.plusNanos(1_000);
    }
}
