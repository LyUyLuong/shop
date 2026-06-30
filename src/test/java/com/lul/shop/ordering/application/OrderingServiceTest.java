package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.application.port.*;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;


public class OrderingServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CART_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");


    @Test
    void shouldCreateOrderSnapshotDecreaseStockAndClearCartWhenPlaceOrder() {
        InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();

        FakeCheckoutCartClient cartClient = new FakeCheckoutCartClient(
                new CheckoutCartSnapshot(
                        CART_ID,
                        USER_ID,
                        List.of(new CheckoutCartItemSnapshot(PRODUCT_ID, 2))
                )
        );

        FakeCheckoutProductClient productClient = new FakeCheckoutProductClient();
        productClient.products.put(PRODUCT_ID, new CheckoutProductSnapshot(
                PRODUCT_ID,
                "SHOP-E2E-001",
                "Workshop Hoodie",
                new BigDecimal("199000.00")
        ));

        OrderingService service = new OrderingService(orderRepository, cartClient, productClient);

        OrderResult result = service.placeOrder(new PlaceOrderCommand(USER_ID));

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.totalAmount()).isEqualByComparingTo("398000.00");
        assertThat(result.items()).hasSize(1);

        assertThat(result.items().get(0).productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.items().get(0).productSku()).isEqualTo("SHOP-E2E-001");
        assertThat(result.items().get(0).productName()).isEqualTo("Workshop Hoodie");
        assertThat(result.items().get(0).unitPrice()).isEqualByComparingTo("199000.00");
        assertThat(result.items().get(0).quantity()).isEqualTo(2);
        assertThat(result.items().get(0).lineTotal()).isEqualByComparingTo("398000.00");

        assertThat(orderRepository.savedOrders).hasSize(1);
        assertThat(cartClient.clearCartCalls).containsExactly(USER_ID);
        assertThat(productClient.stockDecreaseCalls)
                .containsExactly(new StockDecreaseCall(PRODUCT_ID, 2));
    }


    @Test
    void shouldThrowCartEmptyWhenPlaceOrderWithEmptyCart() {
        InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
        FakeCheckoutCartClient cartClient = new FakeCheckoutCartClient(
                new CheckoutCartSnapshot(CART_ID, USER_ID, List.of())
        );
        FakeCheckoutProductClient productClient = new FakeCheckoutProductClient();

        OrderingService service = new OrderingService(orderRepository, cartClient, productClient);

        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderCommand(USER_ID)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrderingErrorCode.CART_EMPTY)
                );

        assertThat(orderRepository.savedOrders).isEmpty();
        assertThat(cartClient.clearCartCalls).isEmpty();
        assertThat(productClient.stockDecreaseCalls).isEmpty();
    }

    @Test
    void shouldThrowInsufficientStockWhenPlaceOrderWithoutEnoughStock() {
        InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();

        FakeCheckoutCartClient cartClient = new FakeCheckoutCartClient(
                new CheckoutCartSnapshot(
                        CART_ID,
                        USER_ID,
                        List.of(new CheckoutCartItemSnapshot(PRODUCT_ID, 2))
                )
        );

        FakeCheckoutProductClient productClient = new FakeCheckoutProductClient();
        productClient.products.put(PRODUCT_ID, new CheckoutProductSnapshot(
                PRODUCT_ID,
                "SHOP-E2E-001",
                "Workshop Hoodie",
                new BigDecimal("199000.00")
        ));
        productClient.productIdsWithoutEnoughStock.add(PRODUCT_ID);

        OrderingService service = new OrderingService(orderRepository, cartClient, productClient);

        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderCommand(USER_ID)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrderingErrorCode.INSUFFICIENT_STOCK)
                );

        assertThat(orderRepository.savedOrders).isEmpty();
        assertThat(cartClient.clearCartCalls).isEmpty();
        assertThat(productClient.stockDecreaseCalls)
                .containsExactly(new StockDecreaseCall(PRODUCT_ID, 2));
    }

    @Test
    void shouldChangePendingOrderToPaidWhenMarkOrderAsPaid() {
        InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();

        Order order = Order.create(
                USER_ID,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "SHOP-E2E-001",
                        "Workshop Hoodie",
                        new BigDecimal("199000.00"),
                        2
                ))
        );
        orderRepository.save(order);

        OrderingService service = new OrderingService(
                orderRepository,
                new FakeCheckoutCartClient(new CheckoutCartSnapshot(CART_ID, USER_ID, List.of())),
                new FakeCheckoutProductClient()
        );

        service.markOrderAsPaid(USER_ID, order.getId());

        Order savedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
    }




    private static class InMemoryOrderRepository implements OrderRepository {

        private final Map<UUID, Order> orders = new LinkedHashMap<>();
        private final List<Order> savedOrders = new ArrayList<>();


        @Override
        public Order save(Order order) {
            orders.put(order.getId(),order);
            savedOrders.add(order);
            return order;
        }

        @Override
        public Optional<Order> findById(UUID orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public Optional<Order> findByIdAndUserId(UUID orderId, UUID userId) {
            return findById(orderId).filter(order -> order.belongsTo(userId));
        }

        @Override
        public List<Order> findByUserId(UUID userId) {
            return orders.values()
                    .stream()
                    .filter(order -> order.belongsTo(userId))
                    .toList();
        }
    }

    private static class FakeCheckoutCartClient implements CheckoutCartClient {

        private final CheckoutCartSnapshot cart;
        private final List<UUID> clearCartCalls  = new ArrayList<>();

        private FakeCheckoutCartClient(CheckoutCartSnapshot cart) {
            this.cart = cart;
        }


        @Override
        public CheckoutCartSnapshot getCartForCheckout(UUID userId) {
            if (!cart.userId().equals(userId)) {
                throw new IllegalArgumentException("unexpected userId");
            }

            return cart;
        }

        @Override
        public void clearCart(UUID userId) {
            clearCartCalls.add(userId);
        }
    }

    private static class FakeCheckoutProductClient implements CheckoutProductClient {

        private final Map<UUID, CheckoutProductSnapshot> products = new HashMap<>();
        private final Set<UUID> productIdsWithoutEnoughStock = new HashSet<>();
        private final List<StockDecreaseCall> stockDecreaseCalls = new ArrayList<>();

        @Override
        public CheckoutProductSnapshot getProductForCheckout(UUID productId) {
            return products.get(productId);
        }

        @Override
        public boolean decreaseStockIfEnough(UUID productId, int quantity) {
            stockDecreaseCalls.add(new StockDecreaseCall(productId, quantity));
            return !productIdsWithoutEnoughStock.contains(productId);
        }
    }

    private record StockDecreaseCall(UUID productId, int quantity) {
    }

}
