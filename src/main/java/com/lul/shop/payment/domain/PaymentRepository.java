package com.lul.shop.payment.domain;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID paymentId);

    Optional<Payment> findByIdAndUserId(UUID paymentId, UUID userId);

    Optional<Payment> findByOrderId(UUID orderId);

}