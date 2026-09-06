package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.exception.CommonErrorCode;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@AutoConfigureMockMvc
class AdminOrderPaginationRepositoryTest extends PostgresIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777701");
    private static final UUID PRODUCT_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777702");
    private static final Instant AT = Instant.parse("2040-01-01T12:00:00Z");

    @Autowired
    private OrderQueryRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void seed() {
        jdbc.update("""
                insert into users
                    (id, email, name, password_hash, enabled, created_at, updated_at)
                values (?, 'admin-pagination@example.com', 'Pagination User',
                        'test-hash', true, now(), now())
                """, USER_ID);
        jdbc.update("""
                insert into products
                    (id, sku, name, description, price, stock_quantity,
                     status, created_at, updated_at)
                values (?, 'ADMIN-PAGE-SKU', 'Pagination Product', 'Test product',
                        100000.00, 100, 'ACTIVE', now(), now())
                """, PRODUCT_ID);

        // Deliberately not inserted in the expected UUID sort order.
        insertOrder(2, OrderStatus.CONFIRMED, AT, 2);
        insertOrder(1, OrderStatus.CONFIRMED, AT, 1);
        insertOrder(3, OrderStatus.CONFIRMED, AT, 3);
        insertOrder(4, OrderStatus.PACKING, AT, 1);
        insertOrder(5, OrderStatus.CONFIRMED, AT.minusSeconds(60), 1);
        insertOrder(6, OrderStatus.CONFIRMED, AT.plusSeconds(60), 1);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void shouldUseIdAsTieBreakerAcrossPagesAndPreserveSummaryFields() {
        PageResult<OrderSummary> first = repository.searchSummaries(
                ties(), new PageQuery(0, 2));
        PageResult<OrderSummary> second = repository.searchSummaries(
                ties(), new PageQuery(1, 2));

        assertThat(first.content()).extracting(OrderSummary::id)
                .containsExactly(id(3), id(2));
        assertThat(second.content()).extracting(OrderSummary::id)
                .containsExactly(id(1));
        assertThat(first.content()).extracting(OrderSummary::itemCount)
                .containsExactly(3, 2);
        assertThat(first.content().get(0).totalAmount())
                .isEqualByComparingTo("300000.00");
        assertThat(first.content()).extracting(OrderSummary::userId)
                .containsOnly(USER_ID);
        assertThat(first.totalElements()).isEqualTo(3L);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.totalElements()).isEqualTo(3L);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void shouldOrderByTimeBeforeIdAndKeepInclusiveDateFilters() {
        OrderSearchCriteria criteria = new OrderSearchCriteria(
                OrderStatus.CONFIRMED, AT.minusSeconds(60), AT.plusSeconds(60));

        PageResult<OrderSummary> result =
                repository.searchSummaries(criteria, new PageQuery(0, 20));

        assertThat(result.content()).extracting(OrderSummary::id)
                .containsExactly(id(6), id(3), id(2), id(1), id(5));
        assertThat(result.content()).extracting(OrderSummary::status)
                .containsOnly(OrderStatus.CONFIRMED);
        assertThat(result.totalElements()).isEqualTo(5L);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();

        PageResult<OrderSummary> allStatuses = repository.searchSummaries(
                new OrderSearchCriteria(null, AT, AT), new PageQuery(0, 20));
        assertThat(allStatuses.content()).extracting(OrderSummary::id)
                .containsExactly(id(4), id(3), id(2), id(1));
        assertThat(allStatuses.totalElements()).isEqualTo(4L);
    }

    @Test
    void shouldKeepCountsWhenPageIsBeyondData() {
        PageResult<OrderSummary> result =
                repository.searchSummaries(ties(), new PageQuery(2, 2));

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3L);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void shouldReturnEmptyMetadataWhenNoOrdersMatch() {
        PageResult<OrderSummary> result = repository.searchSummaries(
                new OrderSearchCriteria(OrderStatus.CANCELLED, AT, AT),
                new PageQuery(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1073741824,4", "2147483647,2"})
    void shouldRejectOverflowBeforeExecutingSql(int page, int size) {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThatThrownBy(() ->
                    repository.searchSummaries(ties(), new PageQuery(page, size)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(CommonErrorCode.INVALID_REQUEST))
                    .hasMessage("Invalid request: page offset is too large");
            assertThat(statistics.getPrepareStatementCount()).isZero();
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void shouldAcceptLargestSupportedOffsetWithoutOverflowingHasNext() {
        PageResult<OrderSummary> result = repository.searchSummaries(
                ties(), new PageQuery(Integer.MAX_VALUE, 1));

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.totalElements()).isEqualTo(3L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/admin/orders,1073741824,4,ROLE_ADMIN",
            "/api/v1/orders/page,107374183,1,ROLE_USER"
    })
    void shouldReturn400ThroughRealServiceAndRepository(
            String endpoint, String page, String suppliedSize, String role)
            throws Exception {
        mockMvc.perform(get(endpoint)
                        .param("page", page)
                        .param("size", suppliedSize)
                        .with(jwt()
                                .jwt(builder -> builder.subject(USER_ID.toString()))
                                .authorities(new SimpleGrantedAuthority(role))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_005"))
                .andExpect(jsonPath("$.error.message")
                        .value("Invalid request: page offset is too large"));
    }

    private OrderSearchCriteria ties() {
        return new OrderSearchCriteria(OrderStatus.CONFIRMED, AT, AT);
    }

    private static UUID id(int suffix) {
        return UUID.fromString(
                "aaaaaaaa-aaaa-4aaa-8aaa-" + String.format("%012d", suffix));
    }

    private void insertOrder(int suffix, OrderStatus status, Instant at, int items) {
        UUID orderId = id(suffix);
        BigDecimal amount = new BigDecimal("100000.00")
                .multiply(BigDecimal.valueOf(items));
        jdbc.update("""
                insert into orders
                    (id, user_id, status, total_amount, expires_at,
                     inventory_released_at, created_at, updated_at,
                     recipient_name, recipient_phone, shipping_address,
                     shipping_method, payment_mode, subtotal_amount, shipping_fee)
                values (?, ?, ?, ?, null, null, ?, ?,
                        'Nguyen Van A', '+84901234567', '123 Nguyen Trai, HCMC',
                        'STANDARD', 'COD', ?, 0.00)
                """,
                orderId, USER_ID, status.name(), amount,
                Timestamp.from(at), Timestamp.from(at), amount);

        for (int index = 0; index < items; index++) {
            jdbc.update("""
                    insert into order_items
                        (id, order_id, product_id, product_sku, product_name,
                         unit_price, quantity, line_total, created_at, updated_at)
                    values (?, ?, ?, ?, 'Pagination Product',
                            100000.00, 1, 100000.00, ?, ?)
                    """,
                    UUID.randomUUID(), orderId, PRODUCT_ID,
                    "SNAPSHOT-" + suffix + "-" + index,
                    Timestamp.from(at), Timestamp.from(at));
        }
    }
}