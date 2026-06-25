package com.lul.shop.payment.infrastructure.persistence.repository;

import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import com.lul.shop.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;
    private final PaymentMapper paymentMapper;

    public PaymentRepositoryImpl(PaymentJpaRepository paymentJpaRepository,
                                 PaymentMapper paymentMapper) {
        this.paymentJpaRepository = paymentJpaRepository;
        this.paymentMapper = paymentMapper;
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        Objects.requireNonNull(payment, "payment must not be null");

        PaymentJpaEntity entity = paymentMapper.toEntity(payment);
        PaymentJpaEntity savedEntity = paymentJpaRepository.save(entity);

        return paymentMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Payment> findById(UUID paymentId) {
        Objects.requireNonNull(paymentId, "paymentId must not be null");

        return paymentJpaRepository.findById(paymentId)
                .map(paymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByIdAndUserId(UUID paymentId, UUID userId) {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        return paymentJpaRepository.findByIdAndUserId(paymentId, userId)
                .map(paymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        return paymentJpaRepository.findByOrderId(orderId)
                .map(paymentMapper::toDomain);
    }

    @Override
    public boolean existsByOrderId(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        return paymentJpaRepository.existsByOrderId(orderId);
    }
}