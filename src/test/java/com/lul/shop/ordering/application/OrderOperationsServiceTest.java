package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.AdminOrderDetailResult;
import com.lul.shop.ordering.application.dto.AdminOrderSummaryResult;
import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.application.dto.OrderStatusHistoryResult;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.lul.shop.ordering.support.OrderingTestFixtures.createMockOrder;
import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderOperationsServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void shouldSearchAdminOrderSummaries() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderStatusHistoryRepository historyRepository = new FakeOrderStatusHistoryRepository();

        OrderSummary summary = new OrderSummary(
                UUID.randomUUID(),
                USER_ID,
                OrderStatus.PAID,
                new BigDecimal("199000.00"),
                2,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:10:00Z")
        );

        orderRepository.searchResult = new PageResult<>(
                List.of(summary),
                0,
                20,
                1,
                1,
                false
        );

        OrderLifecycleService lifecycleService = mock(OrderLifecycleService.class);

        OrderOperationsService service =
        new OrderOperationsService(
                orderRepository,
                historyRepository,
                lifecycleService
        );

        OrderSearchCriteria criteria = new OrderSearchCriteria(OrderStatus.PAID, null, null);
        PageQuery pageQuery = new PageQuery(0, 20);

        PageResult<AdminOrderSummaryResult> result = service.searchOrders(criteria, pageQuery);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(summary.id());
        assertThat(result.content().get(0).status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.content().get(0).itemCount()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(1);

        assertThat(orderRepository.lastSearchCriteria).isEqualTo(criteria);
        assertThat(orderRepository.lastPageQuery).isEqualTo(pageQuery);
    }

    @Test
    void shouldReturnAdminOrderDetail() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderStatusHistoryRepository historyRepository = new FakeOrderStatusHistoryRepository();

        Order order = paidOrder();
        orderRepository.givenOrder(order);

        OrderLifecycleService lifecycleService = mock(OrderLifecycleService.class);

        OrderOperationsService service =
                new OrderOperationsService(
                        orderRepository,
                        historyRepository,
                        lifecycleService
                );

        AdminOrderDetailResult result = service.getOrder(order.getId());

        assertThat(result.id()).isEqualTo(order.getId());
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.paymentMode())
                .isEqualTo(OrderPaymentMode.MOCK);
        assertThat(result.subtotalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(result.shippingFee())
                .isEqualByComparingTo("0.00");
        assertThat(result.totalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(result.fulfillment().recipientName())
                .isEqualTo(fulfillment().recipientName());
        assertThat(result.fulfillment().recipientPhone())
                .isEqualTo(fulfillment().recipientPhone());
        assertThat(result.fulfillment().shippingAddress())
                .isEqualTo(fulfillment().shippingAddress());
        assertThat(result.fulfillment().shippingMethod())
                .isEqualTo(fulfillment().shippingMethod());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productId()).isEqualTo(PRODUCT_ID);
    }


    @Test
    void shouldReturnStatusHistoryAfterCheckingOrderExists() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderStatusHistoryRepository historyRepository = new FakeOrderStatusHistoryRepository();

        Order order = paidOrder();
        orderRepository.givenOrder(order);

        OrderStatusHistory history = OrderStatusHistory.recordAdminChange(
                order.getId(),
                ADMIN_ID,
                OrderStatus.PAID,
                OrderStatus.PACKING,
                "Start packing"
        );
        historyRepository.givenHistory(history);

        OrderLifecycleService lifecycleService = mock(OrderLifecycleService.class);

        OrderOperationsService service =
                new OrderOperationsService(
                        orderRepository,
                        historyRepository,
                        lifecycleService
                );

        List<OrderStatusHistoryResult> result = service.getStatusHistory(order.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderId()).isEqualTo(order.getId());
        assertThat(result.get(0).fromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.get(0).toStatus()).isEqualTo(OrderStatus.PACKING);
    }

    private static Order paidOrder() {
        Order order = createMockOrder(
                USER_ID,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "SKU-001",
                        "Snapshot Product",
                        null,
                        new BigDecimal("199000.00"),
                        1
                )),
                Instant.parse("2026-07-16T10:00:00Z")
        );

        order.markPaid();

        return order;
    }

    private static class FakeOrderRepository implements OrderRepository {

        private final Map<UUID, Order> orders = new LinkedHashMap<>();
        private final List<Order> savedOrders = new ArrayList<>();

        private PageResult<OrderSummary> searchResult = new PageResult<>(List.of(), 0, 20, 0, 0, false);
        private OrderSearchCriteria lastSearchCriteria;
        private PageQuery lastPageQuery;

        private void givenOrder(Order order) {
            orders.put(order.getId(), order);
        }

        @Override
        public Order save(Order order) {
            savedOrders.add(order);
            orders.put(order.getId(), order);
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
        public Optional<Order> findByIdForUpdate(UUID orderId) {
            return findById(orderId);
        }

        @Override
        public Optional<Order> findByIdAndUserIdForUpdate(
                UUID orderId,
                UUID userId
        ) {
            return findByIdAndUserId(orderId, userId);
        }

        @Override
        public List<Order> findByUserId(UUID userId) {
            return orders.values()
                    .stream()
                    .filter(order -> order.belongsTo(userId))
                    .toList();
        }

        @Override
        public PageResult<OrderSummary> searchSummaries(OrderSearchCriteria criteria, PageQuery pageQuery) {
            lastSearchCriteria = criteria;
            lastPageQuery = pageQuery;
            return searchResult;
        }

        @Override
        public List<Order> claimExpiredForUpdate(
                Instant cutoff,
                int limit
        ) {
            throw new UnsupportedOperationException(
                    "claimExpiredForUpdate is not used in this test"
            );
        }
    }

    private static class FakeOrderStatusHistoryRepository implements OrderStatusHistoryRepository {

        private final Map<UUID, List<OrderStatusHistory>> historiesByOrderId = new HashMap<>();
        private final List<OrderStatusHistory> savedHistories = new ArrayList<>();

        private void givenHistory(OrderStatusHistory history) {
            historiesByOrderId
                    .computeIfAbsent(history.getOrderId(), ignored -> new ArrayList<>())
                    .add(history);
        }

        @Override
        public OrderStatusHistory save(OrderStatusHistory history) {
            savedHistories.add(history);
            givenHistory(history);
            return history;
        }

        @Override
        public List<OrderStatusHistory> findTimelineByOrderId(UUID orderId) {
            return historiesByOrderId.getOrDefault(orderId, List.of());
        }
    }
}
