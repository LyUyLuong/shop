package com.lul.shop.ordering.application;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class OrderExpiryBatchProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(OrderExpiryBatchProcessor.class);

    private final OrderRepository orderRepository;
    private final OrderLifecycleService lifecycleService;
    private final Clock clock;

    public OrderExpiryBatchProcessor(
            OrderRepository orderRepository,
            OrderLifecycleService lifecycleService,
            Clock clock
    ) {
        this.orderRepository = Objects.requireNonNull(orderRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public int expireNextBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize must be greater than 0"
            );
        }

        Instant expiredAt = Instant.now(clock);
        List<Order> claimedOrders =
                orderRepository.claimExpiredForUpdate(
                        expiredAt,
                        batchSize
                );

        for (Order claimedOrder : claimedOrders) {
            lifecycleService.expireClaimedBySystem(
                    claimedOrder,
                    expiredAt
            );
        }

        log.info(
                "action=order.expiry_batch_processed "
                        + "expiredAt={} claimedCount={} result=success",
                expiredAt,
                claimedOrders.size()
        );

        return claimedOrders.size();
    }
}