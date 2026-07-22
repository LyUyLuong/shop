package com.lul.shop.payment.application;

import com.lul.shop.payment.domain.PaymentIdempotencyRecord;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentIdempotencyService {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private final PaymentIdempotencyRepository repository;
    private final Clock clock;

    public PaymentIdempotencyService(
            PaymentIdempotencyRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Decision begin(
            UUID userId,
            UUID orderId,
            String idempotencyKey
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");

        validateKey(idempotencyKey);

        String fingerprint = fingerprint(orderId);
        Instant now = clock.instant();

        PaymentIdempotencyRecord candidate =
                PaymentIdempotencyRecord.processing(
                        userId,
                        idempotencyKey,
                        fingerprint,
                        now
                );

        if (repository.insertIfAbsent(candidate)) {
            return Decision.owner(candidate.id());
        }

        PaymentIdempotencyRecord existing = repository
                .findByUserIdAndKey(userId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode
                                .PAYMENT_IDEMPOTENCY_STATE_INVALID
                ));

        if (!existing.matchesFingerprint(fingerprint)) {
            throw new BusinessException(
                    PaymentErrorCode.IDEMPOTENCY_KEY_REUSED
            );
        }

        if (!existing.isCompleted()) {
            throw new BusinessException(
                    PaymentErrorCode
                            .PAYMENT_IDEMPOTENCY_STATE_INVALID
            );
        }

        return Decision.replay(existing.paymentId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID claimId, UUID paymentId) {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");

        boolean completed = repository.complete(
                claimId,
                paymentId,
                clock.instant()
        );

        if (!completed) {
            throw new BusinessException(
                    PaymentErrorCode
                            .PAYMENT_IDEMPOTENCY_STATE_INVALID
            );
        }
    }

    String fingerprint(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        String canonicalRequest =
                "PAYMENT_MOCK\n"
                        + "orderId=" + orderId;

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            canonicalRequest.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private void validateKey(String idempotencyKey) {
        if (
                idempotencyKey == null
                        || !KEY_PATTERN
                        .matcher(idempotencyKey)
                        .matches()
        ) {
            throw new BusinessException(
                    PaymentErrorCode.INVALID_IDEMPOTENCY_KEY
            );
        }
    }

    public record Decision(
            UUID claimId,
            UUID replayPaymentId
    ) {
        public Decision {
            if ((claimId == null) == (replayPaymentId == null)) {
                throw new IllegalArgumentException(
                        "exactly one decision identifier is required"
                );
            }
        }

        public static Decision owner(UUID claimId) {
            return new Decision(
                    Objects.requireNonNull(claimId),
                    null
            );
        }

        public static Decision replay(UUID paymentId) {
            return new Decision(
                    null,
                    Objects.requireNonNull(paymentId)
            );
        }

        public boolean isReplay() {
            return replayPaymentId != null;
        }
    }
}
