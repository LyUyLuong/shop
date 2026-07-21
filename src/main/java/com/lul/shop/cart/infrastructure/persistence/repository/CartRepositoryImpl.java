package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import com.lul.shop.cart.infrastructure.persistence.mapper.CartMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
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
    private final EntityManager entityManager;

    public CartRepositoryImpl(
            CartJpaRepository cartJpaRepository,
            CartMapper cartMapper,
            Clock clock,
            EntityManager entityManager
    ) {
        this.cartJpaRepository = cartJpaRepository;
        this.cartMapper = cartMapper;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Cart> findByUserId(UUID userId) {
        return cartJpaRepository.findByUserId(userId)
                .map(cartMapper::toDomain);
    }

    @Override
    @Transactional(
            propagation = Propagation.MANDATORY,
            readOnly = false
    )
    public Optional<Cart> findByIdAndUserIdForUpdate(
            UUID cartId,
            UUID userId
    ) {
        return cartJpaRepository
                .lockByIdAndUserId(cartId, userId)
                .map(this::detachLockedCart)
                .flatMap(lockedCartId ->
                        cartJpaRepository
                                .findAggregateByIdAndUserId(
                                        lockedCartId,
                                        userId
                                )
                )
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

    private UUID detachLockedCart(CartJpaEntity lockedCart) {
        UUID cartId = lockedCart.getId();

        entityManager.detach(lockedCart);

        return cartId;
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