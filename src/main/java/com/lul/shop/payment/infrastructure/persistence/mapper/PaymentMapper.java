package com.lul.shop.payment.infrastructure.persistence.mapper;

import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    default Payment toDomain(PaymentJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Payment(
                entity.getId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getMethod(),
                entity.getStatus(),
                entity.getAmount(),
                entity.getPaidAt(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default PaymentJpaEntity toEntity(Payment payment) {
        if (payment == null) {
            return null;
        }

        PaymentJpaEntity entity = new PaymentJpaEntity();
        entity.setId(payment.getId());
        entity.setOrderId(payment.getOrderId());
        entity.setUserId(payment.getUserId());
        entity.setMethod(payment.getMethod());
        entity.setStatus(payment.getStatus());
        entity.setAmount(payment.getAmount());
        entity.setPaidAt(payment.getPaidAt());
        entity.setFailureReason(payment.getFailureReason());
        entity.setCreatedAt(payment.getCreatedAt());
        entity.setUpdatedAt(payment.getUpdatedAt());

        return entity;
    }
}