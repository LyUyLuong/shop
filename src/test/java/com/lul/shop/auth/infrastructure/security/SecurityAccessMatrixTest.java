package com.lul.shop.auth.infrastructure.security;

import com.lul.shop.auth.application.AuthService;
import com.lul.shop.auth.presentation.AuthController;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductImageContent;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.catalog.presentation.CatalogController;
import com.lul.shop.ordering.application.OrderItemImageService;
import com.lul.shop.ordering.application.OrderOperationsService;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.AdminOrderSummaryResult;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.presentation.AdminOrderController;
import com.lul.shop.ordering.presentation.OrderItemImageUrlResolver;
import com.lul.shop.ordering.presentation.OrderingController;
import com.lul.shop.shared.config.CorsConfig;
import com.lul.shop.shared.config.CorsProperties;
import com.lul.shop.shared.config.WebConfig;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;


import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.lang.reflect.Method;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = {
        AuthController.class,
        CatalogController.class,
        OrderingController.class,
        AdminOrderController.class
})
@Import({
        WebConfig.class,
        CorsConfig.class,
        SecurityConfig.class,
        JwtConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@EnableConfigurationProperties({
        JwtProperties.class,
        CorsProperties.class
})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "app.jwt.access-token-ttl=PT15M",
        "app.jwt.issuer=shop",
        "app.cors.allowed-origins[0]=http://localhost:5173"
})
class SecurityAccessMatrixTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID PRODUCT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final SimpleGrantedAuthority USER_AUTHORITY =
            new SimpleGrantedAuthority("ROLE_USER");

    private static final SimpleGrantedAuthority ADMIN_AUTHORITY =
            new SimpleGrantedAuthority("ROLE_ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private OrderingService orderingService;

    @MockitoBean
    private OrderOperationsService orderOperationsService;

    @MockitoBean
    private OrderItemImageService orderItemImageService;

    @MockitoBean
    private OrderItemImageUrlResolver orderItemImageUrlResolver;

    @Test
    void shouldAllowPublicAuthRouteWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value("COMMON_001"));

        verifyNoInteractions(authService);
    }

    @Test
    void shouldAllowPublicProductSearchWithoutJwt() throws Exception {
        PageResult<ProductResult> products = emptyPage();

        when(catalogService.searchActiveProducts(
                isNull(),
                eq(new PageQuery(0, 20))
        )).thenReturn(products);

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray());

        verify(catalogService).searchActiveProducts(
                null,
                new PageQuery(0, 20)
        );
    }

    @Test
    void shouldAllowPublicProductDetailAndImageWithoutJwt()
            throws Exception {

        when(catalogService.getActiveProduct(PRODUCT_ID))
                .thenReturn(productResult());

        when(catalogService.getProductImage(PRODUCT_ID))
                .thenReturn(new ProductImageContent(
                        new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        "image/webp",
                        3
                ));

        mockMvc.perform(get(
                        "/api/v1/products/{productId}",
                        PRODUCT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id")
                        .value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.version").value(4));

        mockMvc.perform(get(
                        "/api/v1/products/{productId}/image",
                        PRODUCT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @ParameterizedTest(name = "{0} {1} requires authentication")
    @MethodSource("protectedRoutes")
    void shouldRejectProtectedRouteWithoutJwt(
            HttpMethod method,
            String path
    ) throws Exception {

        mockMvc.perform(request(method, path)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value("COMMON_002"));
    }

    @Test
    void shouldAllowAuthenticatedUserToAccessCustomerOrderRoute()
            throws Exception {

        when(orderingService.getOrders(USER_ID))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt()
                                .jwt(builder -> builder.subject(
                                        USER_ID.toString()
                                ))
                                .authorities(USER_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderingService).getOrders(USER_ID);
    }

    @ParameterizedTest(name = "USER cannot access {0}")
    @MethodSource("adminRoutes")
    void shouldRejectUserFromAdminRoute(String path)
            throws Exception {

        mockMvc.perform(get(path)
                        .with(jwt()
                                .jwt(builder -> builder.subject(
                                        USER_ID.toString()
                                ))
                                .authorities(USER_AUTHORITY)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value("COMMON_003"));
    }

    @Test
    void shouldAllowAdminToAccessAdminProductRoute()
            throws Exception {

        PageResult<ProductResult> products = emptyPage();

        when(catalogService.searchProducts(
                any(ProductSearchCriteria.class),
                eq(new PageQuery(0, 20))
        )).thenReturn(products);

        mockMvc.perform(get("/api/v1/admin/products")
                        .with(jwt()
                                .jwt(builder -> builder.subject(
                                        USER_ID.toString()
                                ))
                                .authorities(ADMIN_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(catalogService).searchProducts(
                any(ProductSearchCriteria.class),
                eq(new PageQuery(0, 20))
        );
    }

    @Test
    void shouldAllowAdminToAccessAdminOrderRoute()
            throws Exception {

        PageResult<AdminOrderSummaryResult> orders = emptyPage();

        when(orderOperationsService.searchOrders(
                any(OrderSearchCriteria.class),
                eq(new PageQuery(0, 20))
        )).thenReturn(orders);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .with(jwt()
                                .jwt(builder -> builder.subject(
                                        USER_ID.toString()
                                ))
                                .authorities(ADMIN_AUTHORITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderOperationsService).searchOrders(
                any(OrderSearchCriteria.class),
                eq(new PageQuery(0, 20))
        );
    }

    @Test
    void shouldAllowCorsPreflightWithoutJwt() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(
                                HttpHeaders.ORIGIN,
                                "http://localhost:5173"
                        )
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                "POST"
                        )
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Content-Type, Idempotency-Key"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("Idempotency-Key")
                ));
    }

    @Test
    void shouldRequireAdminRoleOnEveryAdminHandler() {
        List<Method> adminHandlers = handlerMapping
                .getHandlerMethods()
                .entrySet()
                .stream()
                .filter(entry -> requestPaths(entry.getKey())
                        .stream()
                        .anyMatch(path ->
                                path.equals("/api/v1/admin")
                                        || path.startsWith("/api/v1/admin/")
                        ))
                .map(entry -> entry.getValue().getMethod())
                .toList();

        assertThat(adminHandlers)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "createProduct",
                        "updateProduct",
                        "deactivateProduct",
                        "uploadProductImage",
                        "searchAdminProducts",
                        "getAdminProduct",
                        "searchOrders",
                        "getOrder",
                        "getOrderItemImage",
                        "getStatusHistory",
                        "changeStatus"
                );

        assertThat(adminHandlers).allSatisfy(method -> {
            PreAuthorize annotation =
                    AnnotatedElementUtils.findMergedAnnotation(
                            method,
                            PreAuthorize.class
                    );

            assertThat(annotation)
                    .as("%s must declare @PreAuthorize",
                            method.toGenericString())
                    .isNotNull();

            assertThat(annotation.value())
                    .as("%s must require ADMIN",
                            method.toGenericString())
                    .isEqualTo("hasRole('ADMIN')");
        });
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of(
                        HttpMethod.GET,
                        "/api/v1/cart"
                ),
                Arguments.of(
                        HttpMethod.POST,
                        "/api/v1/orders"
                ),
                Arguments.of(
                        HttpMethod.POST,
                        "/api/v1/payments/mock"
                ),
                Arguments.of(
                        HttpMethod.GET,
                        "/api/v1/admin/products"
                ),
                Arguments.of(
                        HttpMethod.GET,
                        "/api/v1/admin/orders"
                )
        );
    }

    private static Stream<String> adminRoutes() {
        return Stream.of(
                "/api/v1/admin/products",
                "/api/v1/admin/orders"
        );
    }

    private static ProductResult productResult() {
        return new ProductResult(
                PRODUCT_ID,
                4L,
                "SKU-001",
                "Running Shoes",
                "Daily shoes",
                new BigDecimal("199000.00"),
                10,
                ProductStatus.ACTIVE,
                null,
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:00Z")
        );
    }

    private static <T> PageResult<T> emptyPage() {
        return new PageResult<>(
                List.of(),
                0,
                20,
                0,
                0,
                false
        );
    }

    private static Set<String> requestPaths(
            RequestMappingInfo mappingInfo
    ) {
        if (mappingInfo.getPathPatternsCondition() != null) {
            return mappingInfo
                    .getPathPatternsCondition()
                    .getPatternValues();
        }

        if (mappingInfo.getPatternsCondition() != null) {
            return mappingInfo
                    .getPatternsCondition()
                    .getPatterns();
        }

        return Set.of();
    }
}
