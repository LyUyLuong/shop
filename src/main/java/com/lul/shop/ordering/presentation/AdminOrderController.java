package com.lul.shop.ordering.presentation;

import com.lul.shop.ordering.application.OrderItemImageService;
import com.lul.shop.ordering.application.OrderOperationsService;
import com.lul.shop.ordering.application.dto.*;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.presentation.dto.request.ChangeOrderStatusRequest;
import com.lul.shop.ordering.presentation.dto.response.AdminOrderDetailResponse;
import com.lul.shop.ordering.presentation.dto.response.AdminOrderSummaryResponse;
import com.lul.shop.ordering.presentation.dto.response.OrderStatusHistoryResponse;
import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.api.PageResponse;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderOperationsService orderOperationsService;
    private final OrderItemImageService orderItemImageService;
    private final OrderItemImageUrlResolver orderItemImageUrlResolver;

    public AdminOrderController(OrderOperationsService orderOperationsService,
                                OrderItemImageService orderItemImageService,
                                OrderItemImageUrlResolver orderItemImageUrlResolver) {
        this.orderOperationsService = orderOperationsService;
        this.orderItemImageService = orderItemImageService;
        this.orderItemImageUrlResolver = orderItemImageUrlResolver;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<AdminOrderSummaryResponse>> searchOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        OrderSearchCriteria criteria = new OrderSearchCriteria(status, createdFrom, createdTo);

        PageResult<AdminOrderSummaryResult> result = orderOperationsService.searchOrders(
                criteria,
                toPageQuery(page, size)
        );

        PageResponse<AdminOrderSummaryResponse> response = PageResponse.from(
                result.map(AdminOrderSummaryResponse::from)
        );

        return ApiResponse.ok(response);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminOrderDetailResponse> getOrder(@PathVariable UUID orderId) {
        AdminOrderDetailResult result = orderOperationsService.getOrder(orderId);

        return ApiResponse.ok(AdminOrderDetailResponse.from(result, orderItemImageUrlResolver));
    }

    @GetMapping("/{orderId}/items/{orderItemId}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InputStreamResource> getOrderItemImage(@PathVariable UUID orderId,
                                                                 @PathVariable UUID orderItemId) {
        OrderItemImageContent image = orderItemImageService.getAdminOrderItemImage(orderId, orderItemId);

        return imageResponse(image);
    }

    @GetMapping("/{orderId}/status-history")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<OrderStatusHistoryResponse>> getStatusHistory(@PathVariable UUID orderId) {
        List<OrderStatusHistoryResult> result = orderOperationsService.getStatusHistory(orderId);

        List<OrderStatusHistoryResponse> response = result.stream()
                .map(OrderStatusHistoryResponse::from)
                .toList();

        return ApiResponse.ok(response);
    }

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminOrderDetailResponse> changeStatus(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable UUID orderId,
                                                              @Valid @RequestBody ChangeOrderStatusRequest request) {
        ChangeOrderStatusCommand command = new ChangeOrderStatusCommand(
                orderId,
                currentUserId(jwt),
                request.status(),
                request.reason()
        );

        AdminOrderDetailResult result = orderOperationsService.changeStatus(command);

        return ApiResponse.ok(AdminOrderDetailResponse.from(result, orderItemImageUrlResolver));
    }

    private ResponseEntity<InputStreamResource> imageResponse(OrderItemImageContent image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.contentLength())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(new InputStreamResource(image.content()));
    }

    private PageQuery toPageQuery(int page, int size) {
        return new PageQuery(page, Math.min(size, MAX_PAGE_SIZE));
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}