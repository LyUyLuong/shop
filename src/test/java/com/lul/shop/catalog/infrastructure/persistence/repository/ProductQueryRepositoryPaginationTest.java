package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductSearchSlice;
import com.lul.shop.catalog.domain.ProductSearchWindow;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import com.lul.shop.shared.domain.PageQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductQueryRepositoryPaginationTest {

    private static final UUID FIRST_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID SECOND_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID PROBE_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final Instant FIRST_TIME =
            Instant.parse("2026-08-10T12:00:00Z");

    private static final Instant SECOND_TIME =
            Instant.parse("2026-08-10T11:00:00Z");

    private static final Instant PROBE_TIME =
            Instant.parse("2026-08-10T10:00:00Z");

    @Mock
    private EntityManager entityManager;

    @Mock
    private TypedQuery<ProductJpaEntity> productQuery;

    @Mock
    private TypedQuery<Object[]> rankedQuery;

    @InjectMocks
    private ProductQueryRepository repository;

    @Test
    void shouldFetchOneBrowseProbeWithoutCountAndUseLastReturnedRow() {
        ProductJpaEntity first =
                productEntity(FIRST_ID, FIRST_TIME);

        ProductJpaEntity second =
                productEntity(SECOND_ID, SECOND_TIME);

        ProductJpaEntity probe =
                productEntity(PROBE_ID, PROBE_TIME);

        when(entityManager.createQuery(
                anyString(),
                eq(ProductJpaEntity.class)
        )).thenReturn(productQuery);

        when(productQuery.setMaxResults(3))
                .thenReturn(productQuery);

        when(productQuery.getResultList())
                .thenReturn(List.of(first, second, probe));

        ProductSearchSlice<ProductJpaEntity> result =
                repository.search(
                        ProductSearchCriteria.activeOnly(null),
                        ProductSearchWindow.first(2)
                );

        assertThat(result.content())
                .containsExactly(first, second);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextPosition()).contains(
                ProductSearchPosition.browse(
                        SECOND_TIME,
                        SECOND_ID
                )
        );

        verify(productQuery).setMaxResults(3);
        verify(entityManager, times(1)).createQuery(
                anyString(),
                eq(ProductJpaEntity.class)
        );
        verify(entityManager, never()).createQuery(
                anyString(),
                eq(Long.class)
        );
    }

    @Test
    void shouldUseDatabasePriorityFromLastReturnedRankedRow() {
        ProductJpaEntity first =
                productEntity(FIRST_ID, FIRST_TIME);

        ProductJpaEntity second =
                productEntity(SECOND_ID, SECOND_TIME);

        ProductJpaEntity probe =
                productEntity(PROBE_ID, PROBE_TIME);

        when(entityManager.createQuery(
                anyString(),
                eq(Object[].class)
        )).thenReturn(rankedQuery);

        when(rankedQuery.setMaxResults(3))
                .thenReturn(rankedQuery);

        when(rankedQuery.getResultList())
                .thenReturn(List.<Object[]>of(
                        new Object[]{first, 0},
                        new Object[]{second, 1},
                        new Object[]{probe, 2}
                ));

        ProductSearchSlice<ProductJpaEntity> result =
                repository.search(
                        ProductSearchCriteria.activeOnly("phone"),
                        ProductSearchWindow.first(2)
                );

        assertThat(result.content())
                .containsExactly(first, second);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextPosition()).contains(
                ProductSearchPosition.ranked(
                        1,
                        SECOND_TIME,
                        SECOND_ID
                )
        );

        verify(rankedQuery).setMaxResults(3);
        verify(entityManager, times(1)).createQuery(
                anyString(),
                eq(Object[].class)
        );
        verify(entityManager, never()).createQuery(
                anyString(),
                eq(Long.class)
        );
    }

    @Test
    void shouldBindBrowseContinuationTuple() {
        ProductSearchPosition position =
                ProductSearchPosition.browse(
                        SECOND_TIME,
                        SECOND_ID
                );

        ArgumentCaptor<String> jpqlCaptor =
                ArgumentCaptor.forClass(String.class);

        when(entityManager.createQuery(
                jpqlCaptor.capture(),
                eq(ProductJpaEntity.class)
        )).thenReturn(productQuery);

        when(productQuery.setMaxResults(3))
                .thenReturn(productQuery);

        when(productQuery.getResultList())
                .thenReturn(List.of());

        ProductSearchSlice<ProductJpaEntity> result =
                repository.search(
                        ProductSearchCriteria.activeOnly(null),
                        ProductSearchWindow.after(2, position)
                );

        assertThat(result.content()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextPosition()).isEmpty();

        assertThat(jpqlCaptor.getValue())
                .contains(
                        "p.createdAt < :afterCreatedAt",
                        "p.createdAt = :afterCreatedAt",
                        "p.id < :afterId",
                        "p.createdAt DESC",
                        "p.id DESC"
                );

        verify(productQuery).setParameter(
                "afterCreatedAt",
                SECOND_TIME
        );
        verify(productQuery).setParameter(
                "afterId",
                SECOND_ID
        );
    }

    @Test
    void shouldBindRankedContinuationTuple() {
        ProductSearchPosition position =
                ProductSearchPosition.ranked(
                        1,
                        SECOND_TIME,
                        SECOND_ID
                );

        ArgumentCaptor<String> jpqlCaptor =
                ArgumentCaptor.forClass(String.class);

        when(entityManager.createQuery(
                jpqlCaptor.capture(),
                eq(Object[].class)
        )).thenReturn(rankedQuery);

        when(rankedQuery.setMaxResults(3))
                .thenReturn(rankedQuery);

        when(rankedQuery.getResultList())
                .thenReturn(List.of());

        ProductSearchSlice<ProductJpaEntity> result =
                repository.search(
                        ProductSearchCriteria.activeOnly("phone"),
                        ProductSearchWindow.after(2, position)
                );

        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextPosition()).isEmpty();

        assertThat(jpqlCaptor.getValue())
                .contains(
                        ":afterMatchPriority",
                        "p.createdAt < :afterCreatedAt",
                        "p.id < :afterId",
                        "p.createdAt DESC",
                        "p.id DESC"
                );

        verify(rankedQuery).setParameter(
                "afterMatchPriority",
                1
        );
        verify(rankedQuery).setParameter(
                "afterCreatedAt",
                SECOND_TIME
        );
        verify(rankedQuery).setParameter(
                "afterId",
                SECOND_ID
        );
    }

    @Test
    void shouldStopLegacyOffsetOverflowBeforeEntityManagerUse() {
        PageQuery pageQuery =
                new PageQuery(Integer.MAX_VALUE, 2);

        assertThatThrownBy(() ->
                repository.search(
                        ProductSearchCriteria.empty(),
                        pageQuery
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "page offset exceeds JPA's supported integer range"
                );

        verifyNoInteractions(entityManager);
    }

    private static ProductJpaEntity productEntity(
            UUID id,
            Instant createdAt
    ) {
        ProductJpaEntity entity =
                new ProductJpaEntity();

        entity.setId(id);
        entity.setCreatedAt(createdAt);

        return entity;
    }
}