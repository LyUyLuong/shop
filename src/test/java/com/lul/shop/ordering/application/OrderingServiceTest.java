package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.application.port.CheckoutCartClient;
import com.lul.shop.ordering.application.port.CheckoutCartItemSnapshot;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import com.lul.shop.ordering.application.port.CheckoutProductClient;
import com.lul.shop.ordering.application.port.CheckoutProductSnapshot;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderingServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID CART_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_A_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID PRODUCT_B_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final UUID CLAIM_ID = UUID.fromString(
            "55555555-5555-4555-8555-555555555555"
    );

    private static final UUID MISSING_ORDER_ID = UUID.fromString(
            "66666666-6666-4666-8666-666666666666"
    );

    private static final long CART_VERSION = 4L;

    private static final String IDEMPOTENCY_KEY =
            "checkout-request-001";

    private static final Instant NOW =
            Instant.parse("2026-07-16T10:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldCreateOrderFromClaimedCartInDeterministicProductOrder() {
        InMemoryOrderRepository orderRepository =
                new InMemoryOrderRepository();

        FakeOrderIdempotencyRepository
                idempotencyRepository =
                new FakeOrderIdempotencyRepository();

        FakeCheckoutCartClient cartClient =
                new FakeCheckoutCartClient(
                        new CheckoutCartSnapshot(
                                CART_ID,
                                USER_ID,
                                CART_VERSION,
                                List.of(
                                        new CheckoutCartItemSnapshot(
                                                PRODUCT_B_ID,
                                                1
                                        ),
                                        new CheckoutCartItemSnapshot(
                                                PRODUCT_A_ID,
                                                2
                                        )
                                )
                        )
                );

        FakeCheckoutProductClient productClient =
                new FakeCheckoutProductClient();

        productClient.products.put(
                PRODUCT_A_ID,
                product(
                        PRODUCT_A_ID,
                        "SHOP-A-001",
                        "Workshop Hoodie",
                        "199000.00",
                        "products/a/hoodie.jpg"
                )
        );

        productClient.products.put(
                PRODUCT_B_ID,
                product(
                        PRODUCT_B_ID,
                        "SHOP-B-001",
                        "Workshop Cap",
                        "50000.00",
                        "products/b/cap.jpg"
                )
        );

        OrderingService service = newService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyRepository
        );

        OrderResult result =
                service.placeOrder(command());

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.totalAmount())
                .isEqualByComparingTo("448000.00");

        assertThat(result.items())
                .extracting(item -> item.productId())
                .containsExactly(
                        PRODUCT_A_ID,
                        PRODUCT_B_ID
                );

        assertThat(orderRepository.savedOrders)
                .hasSize(1);

        assertThat(
                orderRepository.savedOrders
                        .get(0)
                        .getExpiresAt()
        ).isEqualTo(
                NOW.plusSeconds(30 * 60)
        );

        assertThat(cartClient.claimCalls)
                .containsExactly(
                        new CartClaimCall(
                                USER_ID,
                                CART_ID,
                                CART_VERSION
                        )
                );

        assertThat(productClient.productLookupCalls)
                .containsExactly(
                        PRODUCT_A_ID,
                        PRODUCT_B_ID
                );

        assertThat(productClient.stockDecreaseCalls)
                .containsExactly(
                        new StockDecreaseCall(
                                PRODUCT_A_ID,
                                2
                        ),
                        new StockDecreaseCall(
                                PRODUCT_B_ID,
                                1
                        )
                );

        OrderIdempotencyRecord completedRecord =
                idempotencyRepository
                        .findByUserIdAndKey(
                                USER_ID,
                                IDEMPOTENCY_KEY
                        )
                        .orElseThrow();

        assertThat(completedRecord.status())
                .isEqualTo(
                        OrderIdempotencyRecord.Status.COMPLETED
                );

        assertThat(completedRecord.orderId())
                .isEqualTo(result.id());
    }

    @Test
    void shouldReplayCompletedOrderWithoutClaimingCartAgain() {
        InMemoryOrderRepository orderRepository =
                new InMemoryOrderRepository();

        FakeOrderIdempotencyRepository
                idempotencyRepository =
                new FakeOrderIdempotencyRepository();

        FakeCheckoutCartClient cartClient =
                new FakeCheckoutCartClient(
                        new CheckoutCartSnapshot(
                                CART_ID,
                                USER_ID,
                                CART_VERSION,
                                List.of(
                                        new CheckoutCartItemSnapshot(
                                                PRODUCT_A_ID,
                                                2
                                        )
                                )
                        )
                );

        FakeCheckoutProductClient productClient =
                new FakeCheckoutProductClient();

        productClient.products.put(
                PRODUCT_A_ID,
                product(
                        PRODUCT_A_ID,
                        "SHOP-A-001",
                        "Workshop Hoodie",
                        "199000.00",
                        "products/a/hoodie.jpg"
                )
        );

        OrderingService service = newService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyRepository
        );

        OrderResult firstResult =
                service.placeOrder(command());

        OrderResult replayedResult =
                service.placeOrder(command());

        assertThat(replayedResult.id())
                .isEqualTo(firstResult.id());

        assertThat(replayedResult.totalAmount())
                .isEqualByComparingTo(
                        firstResult.totalAmount()
                );

        assertThat(orderRepository.savedOrders)
                .hasSize(1);

        assertThat(cartClient.claimCalls)
                .hasSize(1);

        assertThat(productClient.productLookupCalls)
                .containsExactly(PRODUCT_A_ID);

        assertThat(productClient.stockDecreaseCalls)
                .containsExactly(
                        new StockDecreaseCall(
                                PRODUCT_A_ID,
                                2
                        )
                );

        assertThat(idempotencyRepository.recordCount())
                .isEqualTo(1);
    }

    @Test
    void shouldThrowCartEmptyAfterClaimingCurrentCartVersion() {
        InMemoryOrderRepository orderRepository =
                new InMemoryOrderRepository();

        FakeOrderIdempotencyRepository
                idempotencyRepository =
                new FakeOrderIdempotencyRepository();

        FakeCheckoutCartClient cartClient =
                new FakeCheckoutCartClient(
                        new CheckoutCartSnapshot(
                                CART_ID,
                                USER_ID,
                                CART_VERSION,
                                List.of()
                        )
                );

        FakeCheckoutProductClient productClient =
                new FakeCheckoutProductClient();

        OrderingService service = newService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyRepository
        );

        assertThatThrownBy(() ->
                service.placeOrder(command())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode.CART_EMPTY
                )
        );

        assertThat(cartClient.claimCalls)
                .containsExactly(
                        new CartClaimCall(
                                USER_ID,
                                CART_ID,
                                CART_VERSION
                        )
                );

        assertThat(productClient.productLookupCalls)
                .isEmpty();

        assertThat(productClient.stockDecreaseCalls)
                .isEmpty();

        assertThat(orderRepository.savedOrders)
                .isEmpty();
    }

    @Test
    void shouldThrowInsufficientStockWithoutSavingOrder() {
        InMemoryOrderRepository orderRepository =
                new InMemoryOrderRepository();

        FakeOrderIdempotencyRepository
                idempotencyRepository =
                new FakeOrderIdempotencyRepository();

        FakeCheckoutCartClient cartClient =
                new FakeCheckoutCartClient(
                        new CheckoutCartSnapshot(
                                CART_ID,
                                USER_ID,
                                CART_VERSION,
                                List.of(
                                        new CheckoutCartItemSnapshot(
                                                PRODUCT_A_ID,
                                                2
                                        )
                                )
                        )
                );

        FakeCheckoutProductClient productClient =
                new FakeCheckoutProductClient();

        productClient.products.put(
                PRODUCT_A_ID,
                product(
                        PRODUCT_A_ID,
                        "SHOP-A-001",
                        "Workshop Hoodie",
                        "199000.00",
                        "products/a/hoodie.jpg"
                )
        );

        productClient.productIdsWithoutEnoughStock
                .add(PRODUCT_A_ID);

        OrderingService service = newService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyRepository
        );

        assertThatThrownBy(() ->
                service.placeOrder(command())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode.INSUFFICIENT_STOCK
                )
        );

        assertThat(cartClient.claimCalls)
                .hasSize(1);

        assertThat(productClient.stockDecreaseCalls)
                .containsExactly(
                        new StockDecreaseCall(
                                PRODUCT_A_ID,
                                2
                        )
                );

        assertThat(orderRepository.savedOrders)
                .isEmpty();
    }

    @Test
    void shouldRejectReplayWhenCompletedOrderIsMissing() {
        InMemoryOrderRepository orderRepository =
                new InMemoryOrderRepository();

        FakeOrderIdempotencyRepository
                idempotencyRepository =
                new FakeOrderIdempotencyRepository();

        FakeCheckoutCartClient cartClient =
                new FakeCheckoutCartClient(
                        new CheckoutCartSnapshot(
                                CART_ID,
                                USER_ID,
                                CART_VERSION,
                                List.of()
                        )
                );

        FakeCheckoutProductClient productClient =
                new FakeCheckoutProductClient();

        OrderPlacementIdempotencyService
                idempotencyService =
                new OrderPlacementIdempotencyService(
                        idempotencyRepository,
                        CLOCK
                );

        String fingerprint =
                idempotencyService.fingerprint(
                        CART_ID,
                        CART_VERSION
                );

        idempotencyRepository.put(
                new OrderIdempotencyRecord(
                        CLAIM_ID,
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        fingerprint,
                        OrderIdempotencyRecord.Status.COMPLETED,
                        MISSING_ORDER_ID,
                        NOW,
                        NOW
                )
        );

        OrderingService service = new OrderingService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyService,
                CLOCK
        );

        assertThatThrownBy(() ->
                service.placeOrder(command())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode
                                .ORDER_IDEMPOTENCY_STATE_INVALID
                )
        );

        assertThat(cartClient.claimCalls).isEmpty();
        assertThat(productClient.productLookupCalls).isEmpty();
        assertThat(productClient.stockDecreaseCalls).isEmpty();
        assertThat(orderRepository.savedOrders).isEmpty();
    }

    private OrderingService newService(
            InMemoryOrderRepository orderRepository,
            FakeCheckoutCartClient cartClient,
            FakeCheckoutProductClient productClient,
            FakeOrderIdempotencyRepository idempotencyRepository
    ) {
        OrderPlacementIdempotencyService
                idempotencyService =
                new OrderPlacementIdempotencyService(
                        idempotencyRepository,
                        CLOCK
                );

        return new OrderingService(
                orderRepository,
                cartClient,
                productClient,
                idempotencyService,
                CLOCK
        );
    }

    private static PlaceOrderCommand command() {
        return new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                CART_VERSION,
                IDEMPOTENCY_KEY
        );
    }

    private static CheckoutProductSnapshot product(
            UUID productId,
            String sku,
            String name,
            String price,
            String imageKey
    ) {
        return new CheckoutProductSnapshot(
                productId,
                sku,
                name,
                new BigDecimal(price),
                imageKey
        );
    }

    private static class InMemoryOrderRepository
            implements OrderRepository {

        private final Map<UUID, Order> orders =
                new LinkedHashMap<>();

        private final List<Order> savedOrders =
                new ArrayList<>();

        @Override
        public Order save(Order order) {
            orders.put(order.getId(), order);
            savedOrders.add(order);
            return order;
        }

        @Override
        public Optional<Order> findById(UUID orderId) {
            return Optional.ofNullable(
                    orders.get(orderId)
            );
        }

        @Override
        public Optional<Order> findByIdAndUserId(
                UUID orderId,
                UUID userId
        ) {
            return findById(orderId)
                    .filter(order ->
                            order.belongsTo(userId)
                    );
        }

        @Override
        public Optional<Order> findByIdForUpdate(
                UUID orderId
        ) {
            return findById(orderId);
        }

        @Override
        public Optional<Order> findByIdAndUserIdForUpdate(
                UUID orderId,
                UUID userId
        ) {
            return findByIdAndUserId(
                    orderId,
                    userId
            );
        }

        @Override
        public List<Order> findByUserId(UUID userId) {
            return orders.values()
                    .stream()
                    .filter(order ->
                            order.belongsTo(userId)
                    )
                    .toList();
        }

        @Override
        public PageResult<OrderSummary> searchSummaries(
                OrderSearchCriteria criteria,
                PageQuery pageQuery
        ) {
            throw new UnsupportedOperationException(
                    "searchSummaries is not used "
                            + "in OrderingServiceTest"
            );
        }

        @Override
        public List<Order> claimExpiredForUpdate(
                Instant cutoff,
                int limit
        ) {
            throw new UnsupportedOperationException(
                    "claimExpiredForUpdate is not used "
                            + "in OrderingServiceTest"
            );
        }
    }

    private static class FakeCheckoutCartClient
            implements CheckoutCartClient {

        private final CheckoutCartSnapshot cart;

        private final List<CartClaimCall> claimCalls =
                new ArrayList<>();

        private FakeCheckoutCartClient(
                CheckoutCartSnapshot cart
        ) {
            this.cart = cart;
        }

        @Override
        public CheckoutCartSnapshot claimForCheckout(
                UUID userId,
                UUID cartId,
                long expectedVersion
        ) {
            claimCalls.add(
                    new CartClaimCall(
                            userId,
                            cartId,
                            expectedVersion
                    )
            );

            if (!cart.userId().equals(userId)) {
                throw new IllegalArgumentException(
                        "unexpected userId"
                );
            }

            if (!cart.id().equals(cartId)) {
                throw new IllegalArgumentException(
                        "unexpected cartId"
                );
            }

            if (cart.version() != expectedVersion) {
                throw new IllegalArgumentException(
                        "unexpected cartVersion"
                );
            }

            return cart;
        }
    }

    private static class FakeCheckoutProductClient
            implements CheckoutProductClient {

        private final Map<
                UUID,
                CheckoutProductSnapshot
                > products = new HashMap<>();

        private final Set<UUID>
                productIdsWithoutEnoughStock =
                new HashSet<>();

        private final List<UUID> productLookupCalls =
                new ArrayList<>();

        private final List<StockDecreaseCall>
                stockDecreaseCalls =
                new ArrayList<>();

        @Override
        public CheckoutProductSnapshot
        getProductForCheckout(UUID productId) {
            productLookupCalls.add(productId);
            return products.get(productId);
        }

        @Override
        public boolean decreaseStockIfEnough(
                UUID productId,
                int quantity
        ) {
            stockDecreaseCalls.add(
                    new StockDecreaseCall(
                            productId,
                            quantity
                    )
            );

            return !productIdsWithoutEnoughStock
                    .contains(productId);
        }
    }

    private static class FakeOrderIdempotencyRepository
            implements OrderIdempotencyRepository {

        private final Map<
                IdempotencyScope,
                OrderIdempotencyRecord
                > records = new LinkedHashMap<>();

        @Override
        public boolean insertIfAbsent(
                OrderIdempotencyRecord record
        ) {
            IdempotencyScope scope =
                    new IdempotencyScope(
                            record.userId(),
                            record.idempotencyKey()
                    );

            return records.putIfAbsent(
                    scope,
                    record
            ) == null;
        }

        @Override
        public Optional<OrderIdempotencyRecord>
        findByUserIdAndKey(
                UUID userId,
                String idempotencyKey
        ) {
            return Optional.ofNullable(
                    records.get(
                            new IdempotencyScope(
                                    userId,
                                    idempotencyKey
                            )
                    )
            );
        }

        @Override
        public boolean complete(
                UUID recordId,
                UUID orderId,
                Instant completedAt
        ) {
            for (
                    Map.Entry<
                            IdempotencyScope,
                            OrderIdempotencyRecord
                            > entry : records.entrySet()
            ) {
                OrderIdempotencyRecord record =
                        entry.getValue();

                if (!record.id().equals(recordId)) {
                    continue;
                }

                if (record.status()
                        != OrderIdempotencyRecord
                        .Status.PROCESSING) {
                    return false;
                }

                Instant updatedAt =
                        completedAt.isBefore(
                                record.updatedAt()
                        )
                                ? record.updatedAt()
                                : completedAt;

                entry.setValue(
                        new OrderIdempotencyRecord(
                                record.id(),
                                record.userId(),
                                record.idempotencyKey(),
                                record.requestFingerprint(),
                                OrderIdempotencyRecord
                                        .Status.COMPLETED,
                                orderId,
                                record.createdAt(),
                                updatedAt
                        )
                );

                return true;
            }

            return false;
        }

        private void put(
                OrderIdempotencyRecord record
        ) {
            records.put(
                    new IdempotencyScope(
                            record.userId(),
                            record.idempotencyKey()
                    ),
                    record
            );
        }

        private int recordCount() {
            return records.size();
        }
    }

    private record CartClaimCall(
            UUID userId,
            UUID cartId,
            long expectedVersion
    ) {
    }

    private record StockDecreaseCall(
            UUID productId,
            int quantity
    ) {
    }

    private record IdempotencyScope(
            UUID userId,
            String idempotencyKey
    ) {
    }
}