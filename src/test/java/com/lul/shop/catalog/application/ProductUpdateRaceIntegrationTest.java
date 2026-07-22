package com.lul.shop.catalog.application;

import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.application.dto.UpdateProductCommand;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.exception.ErrorCode;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

class ProductUpdateRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final long INITIAL_VERSION = 0L;
    private static final int INITIAL_STOCK = 10;
    private static final BigDecimal INITIAL_PRICE =
            new BigDecimal("100000.00");

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductRepository productRepository;

    private ProductRepository productRepositorySpy;
    private UUID productId;

    @BeforeEach
    void setUp() {
        productRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        productRepository
                );

        productId = UUID.randomUUID();
        insertProduct();
    }

    @AfterEach
    void cleanDatabase() {
        if (productId != null) {
            jdbcTemplate.update(
                    "delete from products where id = ?",
                    productId
            );
        }
    }

    @Test
    void shouldAllowOnlyOneConcurrentAdminEditForSameVersion()
            throws Exception {
        ProductReadGate gate = gateProductReads(2);
        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<UpdateAttempt> firstWorker = executor.submit(
                () -> attemptUpdate(
                        "Concurrent Admin Update A",
                        "110000.00"
                )
        );

        Future<UpdateAttempt> secondWorker = executor.submit(
                () -> attemptUpdate(
                        "Concurrent Admin Update B",
                        "120000.00"
                )
        );

        try {
            await(
                    gate.readsCompleted(),
                    "both admin product reads"
            );

            gate.releaseReads().countDown();

            UpdateAttempt firstAttempt =
                    firstWorker.get(20, TimeUnit.SECONDS);
            UpdateAttempt secondAttempt =
                    secondWorker.get(20, TimeUnit.SECONDS);

            List<UpdateAttempt> attempts =
                    List.of(firstAttempt, secondAttempt);

            List<UpdateAttempt> successes = attempts.stream()
                    .filter(UpdateAttempt::succeeded)
                    .toList();

            List<UpdateAttempt> failures = attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .toList();

            assertThat(successes).hasSize(1);
            assertThat(failures).hasSize(1);

            UpdateAttempt winner = successes.get(0);
            UpdateAttempt staleWriter = failures.get(0);

            assertThat(winner.result().version())
                    .isEqualTo(1L);

            assertVersionConflict(staleWriter);

            ProductState persisted = loadProductState();

            assertThat(persisted.name())
                    .isEqualTo(winner.result().name());

            assertThat(persisted.price())
                    .isEqualByComparingTo(
                            winner.result().price()
                    );

            assertThat(persisted.stockQuantity())
                    .isEqualTo(INITIAL_STOCK);

            assertThat(persisted.version())
                    .isEqualTo(1L);
        } finally {
            gate.releaseReads().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldRejectStaleAdminEditAfterAtomicStockMutation()
            throws Exception {
        ProductReadGate gate = gateProductReads(1);
        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        Future<UpdateAttempt> adminWorker = executor.submit(
                () -> attemptUpdate(
                        "Stale Admin Update",
                        "175000.00"
                )
        );

        try {
            await(
                    gate.readsCompleted(),
                    "stale admin product read"
            );

            boolean stockDecreased =
                    catalogService.decreaseStockIfEnough(
                            productId,
                            3
                    );

            assertThat(stockDecreased).isTrue();

            gate.releaseReads().countDown();

            UpdateAttempt staleAdmin =
                    adminWorker.get(20, TimeUnit.SECONDS);

            assertVersionConflict(staleAdmin);

            ProductState persisted = loadProductState();

            assertThat(persisted.name())
                    .isEqualTo("Product Race Fixture");

            assertThat(persisted.price())
                    .isEqualByComparingTo(INITIAL_PRICE);

            assertThat(persisted.stockQuantity())
                    .isEqualTo(7);

            assertThat(persisted.version())
                    .isEqualTo(1L);
        } finally {
            gate.releaseReads().countDown();
            shutdown(executor);
        }
    }

    private ProductReadGate gateProductReads(
            int requiredReads
    ) {
        CountDownLatch readsCompleted =
                new CountDownLatch(requiredReads);

        CountDownLatch releaseReads =
                new CountDownLatch(1);

        AtomicInteger interceptedReads =
                new AtomicInteger();

        doAnswer(invocation -> {
            Object loadedProduct =
                    invocation.callRealMethod();

            int currentRead =
                    interceptedReads.incrementAndGet();

            if (currentRead <= requiredReads) {
                readsCompleted.countDown();

                await(
                        releaseReads,
                        "release intercepted product reads"
                );
            }

            return loadedProduct;
        }).when(productRepositorySpy).findById(
                eq(productId)
        );

        return new ProductReadGate(
                readsCompleted,
                releaseReads
        );
    }

    private UpdateAttempt attemptUpdate(
            String name,
            String price
    ) {
        try {
            ProductResult result =
                    catalogService.updateProduct(
                            productId,
                            new UpdateProductCommand(
                                    productSku(),
                                    name,
                                    "Updated during product race test",
                                    new BigDecimal(price),
                                    INITIAL_STOCK,
                                    INITIAL_VERSION
                            )
                    );

            return new UpdateAttempt(result, null);
        } catch (BusinessException exception) {
            return new UpdateAttempt(
                    null,
                    exception.getErrorCode()
            );
        }
    }

    private void assertVersionConflict(
            UpdateAttempt attempt
    ) {
        assertThat(attempt.result()).isNull();

        assertThat(attempt.errorCode())
                .isEqualTo(
                        CatalogErrorCode.PRODUCT_VERSION_CONFLICT
                );

        assertThat(attempt.errorCode().getHttpStatus())
                .isEqualTo(409);
    }

    private void insertProduct() {
        jdbcTemplate.update(
                """
                insert into products (
                    id, version, sku, name, description,
                    price, stock_quantity, status,
                    image_key, image_url,
                    created_at, updated_at
                )
                values (
                    ?, 0, ?, ?, ?,
                    ?, ?, 'ACTIVE',
                    null, null,
                    now(), now()
                )
                """,
                productId,
                productSku(),
                "Product Race Fixture",
                "Product used by product race tests",
                INITIAL_PRICE,
                INITIAL_STOCK
        );
    }

    private ProductState loadProductState() {
        ProductState state = jdbcTemplate.queryForObject(
                """
                select name, price, stock_quantity, version
                from products
                where id = ?
                """,
                (resultSet, rowNumber) -> new ProductState(
                        resultSet.getString("name"),
                        resultSet.getBigDecimal("price"),
                        resultSet.getInt("stock_quantity"),
                        resultSet.getLong("version")
                ),
                productId
        );

        return Objects.requireNonNull(
                state,
                "persisted product state must not be null"
        );
    }

    private String productSku() {
        return "PRODUCT-RACE-" + productId;
    }

    private void await(
            CountDownLatch latch,
            String operation
    ) {
        try {
            boolean completed = latch.await(
                    20,
                    TimeUnit.SECONDS
            );

            if (!completed) {
                throw new IllegalStateException(
                        operation + " timed out"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    operation + " was interrupted",
                    exception
            );
        }
    }

    private void shutdown(
            ExecutorService executor
    ) {
        executor.shutdownNow();

        try {
            executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProductReadGate(
            CountDownLatch readsCompleted,
            CountDownLatch releaseReads
    ) {
    }

    private record UpdateAttempt(
            ProductResult result,
            ErrorCode errorCode
    ) {

        private boolean succeeded() {
            return result != null;
        }
    }

    private record ProductState(
            String name,
            BigDecimal price,
            int stockQuantity,
            long version
    ) {
    }
}
