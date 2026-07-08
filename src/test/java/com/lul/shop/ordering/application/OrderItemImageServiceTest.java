package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderItemImageContent;
import com.lul.shop.ordering.application.port.OrderItemImageClient;
import com.lul.shop.ordering.domain.*;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class OrderItemImageServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String IMAGE_KEY = "products/33333333-3333-4333-8333-333333333333/hoodie.jpg";

    @Test
    void shouldLoadCustomerOrderItemImageWhenOrderBelongsToUserAndItemHasImage() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderItemImageClient imageClient = new FakeOrderItemImageClient();

        Order order = orderWithImage(USER_ID, IMAGE_KEY);
        UUID orderItemId = order.getItems().get(0).getId();
        orderRepository.givenOrder(order);

        OrderItemImageService service = new OrderItemImageService(orderRepository, imageClient);

        OrderItemImageContent result = service.getCustomerOrderItemImage(USER_ID, order.getId(), orderItemId);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.contentLength()).isEqualTo(5);
        assertThat(imageClient.lastRequestedKey).isEqualTo(IMAGE_KEY);
    }

    @Test
    void shouldRejectCustomerOrderItemImageWhenOrderDoesNotBelongToUser() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderItemImageClient imageClient = new FakeOrderItemImageClient();

        Order order = orderWithImage(USER_ID, IMAGE_KEY);
        UUID orderItemId = order.getItems().get(0).getId();
        orderRepository.givenOrder(order);

        OrderItemImageService service = new OrderItemImageService(orderRepository, imageClient);

        assertThatThrownBy(() -> service.getCustomerOrderItemImage(OTHER_USER_ID, order.getId(), orderItemId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND)
                );

        assertThat(imageClient.lastRequestedKey).isNull();
    }

    @Test
    void shouldRejectOrderItemImageWhenItemDoesNotExistInOrder() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderItemImageClient imageClient = new FakeOrderItemImageClient();

        Order order = orderWithImage(USER_ID, IMAGE_KEY);
        orderRepository.givenOrder(order);

        OrderItemImageService service = new OrderItemImageService(orderRepository, imageClient);

        assertThatThrownBy(() -> service.getCustomerOrderItemImage(
                USER_ID,
                order.getId(),
                UUID.fromString("44444444-4444-4444-8444-444444444444")
        ))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrderingErrorCode.ORDER_ITEM_NOT_FOUND)
                );

        assertThat(imageClient.lastRequestedKey).isNull();
    }

    @Test
    void shouldRejectOrderItemImageWhenItemHasNoSnapshotImageKey() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderItemImageClient imageClient = new FakeOrderItemImageClient();

        Order order = orderWithImage(USER_ID, null);
        UUID orderItemId = order.getItems().get(0).getId();
        orderRepository.givenOrder(order);

        OrderItemImageService service = new OrderItemImageService(orderRepository, imageClient);

        assertThatThrownBy(() -> service.getCustomerOrderItemImage(USER_ID, order.getId(), orderItemId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrderingErrorCode.ORDER_ITEM_IMAGE_NOT_FOUND)
                );

        assertThat(imageClient.lastRequestedKey).isNull();
    }

    @Test
    void shouldLoadAdminOrderItemImageWhenOrderExistsAndItemHasImage() {
        FakeOrderRepository orderRepository = new FakeOrderRepository();
        FakeOrderItemImageClient imageClient = new FakeOrderItemImageClient();

        Order order = orderWithImage(USER_ID, IMAGE_KEY);
        UUID orderItemId = order.getItems().get(0).getId();
        orderRepository.givenOrder(order);

        OrderItemImageService service = new OrderItemImageService(orderRepository, imageClient);

        OrderItemImageContent result = service.getAdminOrderItemImage(order.getId(), orderItemId);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(imageClient.lastRequestedKey).isEqualTo(IMAGE_KEY);
    }

    private static Order orderWithImage(UUID userId, String imageKey) {
        return Order.create(
                userId,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "SKU-001",
                        "Snapshot Product",
                        imageKey,
                        new BigDecimal("199000.00"),
                        1
                ))
        );
    }

    private static class FakeOrderRepository implements OrderRepository {

        private final Map<UUID, Order> orders = new LinkedHashMap<>();

        private void givenOrder(Order order) {
            orders.put(order.getId(), order);
        }

        @Override
        public Order save(Order order) {
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
        public List<Order> findByUserId(UUID userId) {
            return orders.values()
                    .stream()
                    .filter(order -> order.belongsTo(userId))
                    .toList();
        }

        @Override
        public PageResult<OrderSummary> searchSummaries(OrderSearchCriteria criteria, PageQuery pageQuery) {
            throw new UnsupportedOperationException("searchSummaries is not used in OrderItemImageServiceTest");
        }
    }

    private static class FakeOrderItemImageClient implements OrderItemImageClient {

        private String lastRequestedKey;

        @Override
        public OrderItemImageContent loadOrderItemImage(String imageKey) {
            lastRequestedKey = imageKey;

            return new OrderItemImageContent(
                    new ByteArrayInputStream("image".getBytes(StandardCharsets.UTF_8)),
                    "image/jpeg",
                    5
            );
        }
    }
}