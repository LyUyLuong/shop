package com.lul.shop.ordering.application;

import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
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
public class OrderPlacementIdempotencyService {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private final OrderIdempotencyRepository repository;
    private final Clock clock;

    public OrderPlacementIdempotencyService(
            OrderIdempotencyRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Decision begin(
            UUID userId,
            UUID cartId,
            long cartVersion,
            String idempotencyKey
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(cartId, "cartId must not be null");

        if (cartVersion < 0) {
            throw new IllegalArgumentException(
                    "cartVersion must not be negative"
            );
        }

        validateKey(idempotencyKey);

        String fingerprint = fingerprint(cartId, cartVersion);
        Instant now = clock.instant();

        OrderIdempotencyRecord candidate =
                OrderIdempotencyRecord.processing(
                        userId,
                        idempotencyKey,
                        fingerprint,
                        now
                );

        if (repository.insertIfAbsent(candidate)) {
            return Decision.owner(candidate.id());
        }

        OrderIdempotencyRecord existing = repository
                .findByUserIdAndKey(userId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(
                        OrderingErrorCode.ORDER_IDEMPOTENCY_STATE_INVALID
                ));

        if (!existing.matchesFingerprint(fingerprint)) {
            throw new BusinessException(
                    OrderingErrorCode.IDEMPOTENCY_KEY_REUSED
            );
        }

        if (!existing.isCompleted()) {
            throw new BusinessException(
                    OrderingErrorCode.ORDER_IDEMPOTENCY_STATE_INVALID
            );
        }

        return Decision.replay(existing.orderId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID claimId, UUID orderId) {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");

        boolean completed = repository.complete(
                claimId,
                orderId,
                clock.instant()
        );

        if (!completed) {
            throw new BusinessException(
                    OrderingErrorCode.ORDER_IDEMPOTENCY_STATE_INVALID
            );
        }
    }

    String fingerprint(UUID cartId, long cartVersion) {
        Objects.requireNonNull(cartId, "cartId must not be null");

        if (cartVersion < 0) {
            throw new IllegalArgumentException(
                    "cartVersion must not be negative"
            );
        }

        String canonicalRequest =
                "ORDER_PLACE\n"
                        + "cartId=" + cartId + "\n"
                        + "cartVersion=" + cartVersion;

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
                    OrderingErrorCode.INVALID_IDEMPOTENCY_KEY
            );
        }
    }

    public record Decision(
            UUID claimId,
            UUID replayOrderId
    ) {
        public Decision {
            if ((claimId == null) == (replayOrderId == null)) {
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

        public static Decision replay(UUID orderId) {
            return new Decision(
                    null,
                    Objects.requireNonNull(orderId)
            );
        }

        public boolean isReplay() {
            return replayOrderId != null;
        }
    }
}