package com.lul.shop.auth.infrastructure.security;

import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.AddCartItemCommand;
import com.lul.shop.cart.application.dto.UpdateCartItemCommand;
import com.lul.shop.cart.presentation.CartController;
import com.lul.shop.cart.presentation.dto.request.AddCartItemRequest;
import com.lul.shop.cart.presentation.dto.request.UpdateCartItemRequest;
import com.lul.shop.ordering.application.OrderItemImageService;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.presentation.OrderItemImageUrlResolver;
import com.lul.shop.ordering.presentation.OrderingController;
import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.presentation.PaymentController;
import com.lul.shop.payment.presentation.dto.request.PayMockPaymentRequest;
import com.lul.shop.ordering.presentation.dto.request.PlaceOrderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOwnershipContractTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID PRODUCT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID CART_ITEM_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final UUID ORDER_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final UUID ORDER_ITEM_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    private static final UUID PAYMENT_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");

    private static final UUID CART_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    private static final long CART_VERSION = 5L;

    private static final String ORDER_KEY = "order-attempt-0001";
    private static final String PAYMENT_KEY = "payment-attempt-0001";

    @Mock
    private CartService cartService;

    @Mock
    private OrderingService orderingService;

    @Mock
    private OrderItemImageService orderItemImageService;

    @Mock
    private OrderItemImageUrlResolver orderItemImageUrlResolver;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private CartController cartController;

    @InjectMocks
    private OrderingController orderingController;

    @InjectMocks
    private PaymentController paymentController;

    @Test
    void shouldGetCartForJwtSubject() {
        RuntimeException probe = probe();

        when(cartService.getCart(USER_ID)).thenThrow(probe);

        assertThatThrownBy(() -> cartController.getCart(jwt()))
                .isSameAs(probe);

        verify(cartService).getCart(USER_ID);
    }

    @Test
    void shouldAddCartItemForJwtSubject() {
        RuntimeException probe = probe();

        AddCartItemCommand expected =
                new AddCartItemCommand(USER_ID, PRODUCT_ID, 2);

        when(cartService.addItem(expected)).thenThrow(probe);

        assertThatThrownBy(() -> cartController.addItem(
                jwt(),
                new AddCartItemRequest(PRODUCT_ID, 2)
        )).isSameAs(probe);

        verify(cartService).addItem(expected);
    }

    @Test
    void shouldUpdateCartItemForJwtSubject() {
        RuntimeException probe = probe();

        UpdateCartItemCommand expected =
                new UpdateCartItemCommand(USER_ID, CART_ITEM_ID, 3);

        when(cartService.updateItem(expected)).thenThrow(probe);

        assertThatThrownBy(() -> cartController.updateItem(
                jwt(),
                CART_ITEM_ID,
                new UpdateCartItemRequest(3)
        )).isSameAs(probe);

        verify(cartService).updateItem(expected);
    }

    @Test
    void shouldRemoveCartItemForJwtSubject() {
        RuntimeException probe = probe();

        when(cartService.removeItem(USER_ID, CART_ITEM_ID))
                .thenThrow(probe);

        assertThatThrownBy(() -> cartController.removeItem(
                jwt(),
                CART_ITEM_ID
        )).isSameAs(probe);

        verify(cartService).removeItem(USER_ID, CART_ITEM_ID);
    }

    @Test
    void shouldPlaceOrderForJwtSubject() {
        RuntimeException probe = probe();

        PlaceOrderCommand expected = new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                CART_VERSION,
                ORDER_KEY,
                fulfillment(),
                OrderPaymentMode.MOCK
        );

        when(orderingService.placeOrder(expected)).thenThrow(probe);

        assertThatThrownBy(() -> orderingController.placeOrder(
                jwt(),
                ORDER_KEY,
                new PlaceOrderRequest(
                        CART_ID,
                        CART_VERSION,
                        fulfillment().recipientName(),
                        fulfillment().recipientPhone(),
                        fulfillment().shippingAddress(),
                        fulfillment().shippingMethod(),
                        OrderPaymentMode.MOCK
                )
        )).isSameAs(probe);

        verify(orderingService).placeOrder(expected);
    }

    @Test
    void shouldGetOrdersForJwtSubject() {
        RuntimeException probe = probe();

        when(orderingService.getOrders(USER_ID)).thenThrow(probe);

        assertThatThrownBy(() -> orderingController.getOrders(jwt()))
                .isSameAs(probe);

        verify(orderingService).getOrders(USER_ID);
    }

    @Test
    void shouldGetOrderForJwtSubject() {
        RuntimeException probe = probe();

        when(orderingService.getOrder(USER_ID, ORDER_ID))
                .thenThrow(probe);

        assertThatThrownBy(() -> orderingController.getOrder(
                jwt(),
                ORDER_ID
        )).isSameAs(probe);

        verify(orderingService).getOrder(USER_ID, ORDER_ID);
    }

    @Test
    void shouldGetOrderItemImageForJwtSubject() {
        RuntimeException probe = probe();

        when(orderItemImageService.getCustomerOrderItemImage(
                USER_ID,
                ORDER_ID,
                ORDER_ITEM_ID
        )).thenThrow(probe);

        assertThatThrownBy(() ->
                orderingController.getOrderItemImage(
                        jwt(),
                        ORDER_ID,
                        ORDER_ITEM_ID
                )
        ).isSameAs(probe);

        verify(orderItemImageService).getCustomerOrderItemImage(
                USER_ID,
                ORDER_ID,
                ORDER_ITEM_ID
        );
    }

    @Test
    void shouldPayOrderForJwtSubject() {
        RuntimeException probe = probe();

        PayOrderCommand expected =
                new PayOrderCommand(USER_ID, ORDER_ID, PAYMENT_KEY);

        when(paymentService.payMock(expected)).thenThrow(probe);

        assertThatThrownBy(() -> paymentController.payMock(
                jwt(),
                PAYMENT_KEY,
                new PayMockPaymentRequest(ORDER_ID)
        )).isSameAs(probe);

        verify(paymentService).payMock(expected);
    }

    @Test
    void shouldGetPaymentForJwtSubject() {
        RuntimeException probe = probe();

        when(paymentService.getPayment(USER_ID, PAYMENT_ID))
                .thenThrow(probe);

        assertThatThrownBy(() -> paymentController.getPayment(
                jwt(),
                PAYMENT_ID
        )).isSameAs(probe);

        verify(paymentService).getPayment(USER_ID, PAYMENT_ID);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.parse("2026-07-15T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-15T00:15:00Z"))
                .build();
    }

    private static RuntimeException probe() {
        return new RuntimeException("Stop after verifying service call");
    }
}
