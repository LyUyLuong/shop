package com.lul.shop.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.domain.OutboxEventType;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PayableOrderSnapshot;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PaymentServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant PAID_AT = Instant.parse("2026-06-29T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(PAID_AT, ZoneOffset.UTC);

    @Test
    void shouldCreateSucceededPaymentMarkOrderPaidAndRecordOutboxEventWhenPayMock() throws Exception {
        FakePaymentRepository paymentRepository = new FakePaymentRepository();

        FakePayableOrderClient orderClient = new FakePayableOrderClient(
                new PayableOrderSnapshot(
                        ORDER_ID,
                        USER_ID,
                        new BigDecimal("398000.00"),
                        true
                )
        );

        FakeOutboxEventRepository outboxEventRepository = new FakeOutboxEventRepository();
        OutboxService outboxService = new OutboxService(outboxEventRepository, objectMapper);

        PaymentService service = new PaymentService(
                paymentRepository,
                orderClient,
                outboxService,
                clock
        );

        PaymentResult result = service.payMock(new PayOrderCommand(USER_ID, ORDER_ID));

        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.method()).isEqualTo(PaymentMethod.MOCK);
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.amount()).isEqualByComparingTo("398000.00");
        assertThat(result.paidAt()).isEqualTo(PAID_AT);
        assertThat(result.failureReason()).isNull();

        assertThat(paymentRepository.savedPayments).hasSize(1);
        assertThat(orderClient.markPaidCalls)
                .containsExactly(new MarkPaidCall(USER_ID, ORDER_ID));

        assertThat(outboxEventRepository.savedEvents).hasSize(1);

        OutboxEvent event = outboxEventRepository.savedEvents.get(0);
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.ORDER_PAID);
        assertThat(event.getAggregateType()).isEqualTo("ORDER");
        assertThat(event.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("eventType").asText()).isEqualTo("ORDER_PAID");
        assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(payload.get("paymentId").asText()).isEqualTo(result.id().toString());
        assertThat(payload.get("userId").asText()).isEqualTo(USER_ID.toString());
    }

    @Test
    void shouldThrowPaymentAlreadyExistsWhenPaymentExistsForOrder() {
        FakePaymentRepository paymentRepository = new FakePaymentRepository();
        paymentRepository.orderIdsWithExistingPayment.add(ORDER_ID);

        FakePayableOrderClient orderClient = new FakePayableOrderClient(
                new PayableOrderSnapshot(
                        ORDER_ID,
                        USER_ID,
                        new BigDecimal("398000.00"),
                        true
                )
        );

        FakeOutboxEventRepository outboxEventRepository = new FakeOutboxEventRepository();

        PaymentService service = new PaymentService(
                paymentRepository,
                orderClient,
                new OutboxService(outboxEventRepository, objectMapper),
                clock
        );

        assertThatThrownBy(() -> service.payMock(new PayOrderCommand(USER_ID, ORDER_ID)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_EXISTS)
                );

        assertThat(paymentRepository.savedPayments).isEmpty();
        assertThat(orderClient.markPaidCalls).isEmpty();
        assertThat(outboxEventRepository.savedEvents).isEmpty();
    }

    @Test
    void shouldThrowOrderNotPayableWhenOrderIsNotPayable() {
        FakePaymentRepository paymentRepository = new FakePaymentRepository();

        FakePayableOrderClient orderClient = new FakePayableOrderClient(
                new PayableOrderSnapshot(
                        ORDER_ID,
                        USER_ID,
                        new BigDecimal("398000.00"),
                        false
                )
        );

        FakeOutboxEventRepository outboxEventRepository = new FakeOutboxEventRepository();

        PaymentService service = new PaymentService(
                paymentRepository,
                orderClient,
                new OutboxService(outboxEventRepository, objectMapper),
                clock
        );

        assertThatThrownBy(() -> service.payMock(new PayOrderCommand(USER_ID, ORDER_ID)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.ORDER_NOT_PAYABLE)
                );

        assertThat(paymentRepository.savedPayments).isEmpty();
        assertThat(orderClient.markPaidCalls).isEmpty();
        assertThat(outboxEventRepository.savedEvents).isEmpty();
    }

    private static class FakePaymentRepository implements PaymentRepository {

        private final Map<UUID, Payment> payments = new LinkedHashMap<>();
        private final Set<UUID> orderIdsWithExistingPayment = new HashSet<>();
        private final List<Payment> savedPayments = new ArrayList<>();

        @Override
        public Payment save(Payment payment) {
            payments.put(payment.getId(), payment);
            orderIdsWithExistingPayment.add(payment.getOrderId());
            savedPayments.add(payment);
            return payment;
        }

        @Override
        public Optional<Payment> findById(UUID paymentId) {
            return Optional.ofNullable(payments.get(paymentId));
        }

        @Override
        public Optional<Payment> findByIdAndUserId(UUID paymentId, UUID userId) {
            return findById(paymentId).filter(payment -> payment.belongsTo(userId));
        }

        @Override
        public Optional<Payment> findByOrderId(UUID orderId) {
            return payments.values()
                    .stream()
                    .filter(payment -> payment.getOrderId().equals(orderId))
                    .findFirst();
        }

        @Override
        public boolean existsByOrderId(UUID orderId) {
            return orderIdsWithExistingPayment.contains(orderId);
        }
    }

    private static class FakePayableOrderClient implements PayableOrderClient {

        private final PayableOrderSnapshot order;
        private final List<MarkPaidCall> markPaidCalls = new ArrayList<>();

        private FakePayableOrderClient(PayableOrderSnapshot order) {
            this.order = order;
        }

        @Override
        public PayableOrderSnapshot getPayableOrder(UUID userId, UUID orderId) {
            return order;
        }

        @Override
        public void markOrderAsPaid(UUID userId, UUID orderId) {
            markPaidCalls.add(new MarkPaidCall(userId, orderId));
        }
    }

    private static class FakeOutboxEventRepository implements OutboxEventRepository {

        private final List<OutboxEvent> savedEvents = new ArrayList<>();

        @Override
        public OutboxEvent save(OutboxEvent event) {
            savedEvents.add(event);
            return event;
        }

        @Override
        public List<OutboxEvent> findPublishableEvents(int limit, int maxRetryCount) {
            throw new UnsupportedOperationException("findPublishableEvents is not used in PaymentServiceTest");
        }
    }

    private record MarkPaidCall(UUID userId, UUID orderId) {
    }
}