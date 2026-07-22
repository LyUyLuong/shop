package com.lul.shop.ordering.presentation;

import com.lul.shop.ordering.application.OrderItemImageService;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.OrderItemImageContent;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.presentation.dto.request.PlaceOrderRequest;
import com.lul.shop.ordering.presentation.dto.response.OrderResponse;
import com.lul.shop.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/orders")
public class OrderingController {

    private final OrderingService orderingService;
    private final OrderItemImageService orderItemImageService;
    private final OrderItemImageUrlResolver orderItemImageUrlResolver;

    public OrderingController(OrderingService orderingService,
                              OrderItemImageService orderItemImageService,
                              OrderItemImageUrlResolver orderItemImageUrlResolver) {
        this.orderingService = orderingService;
        this.orderItemImageService = orderItemImageService;
        this.orderItemImageUrlResolver = orderItemImageUrlResolver;
    }

    @PostMapping
    public ApiResponse<OrderResponse> placeOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key")
            String idempotencyKey,
            @Valid @RequestBody
            PlaceOrderRequest request
    ) {
        PlaceOrderCommand command = new PlaceOrderCommand(
                currentUserId(jwt),
                request.cartId(),
                request.cartVersion(),
                idempotencyKey
        );

        OrderResult result =
                orderingService.placeOrder(command);

        return ApiResponse.ok(
                OrderResponse.from(
                        result,
                        orderItemImageUrlResolver
                )
        );
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> getOrders(@AuthenticationPrincipal Jwt jwt) {
        List<OrderResponse> response = orderingService.getOrders(currentUserId(jwt))
                .stream()
                .map(result -> OrderResponse.from(result, orderItemImageUrlResolver))
                .toList();

        return ApiResponse.ok(response);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getOrder(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID orderId) {
        OrderResult result = orderingService.getOrder(currentUserId(jwt), orderId);

        return ApiResponse.ok(OrderResponse.from(result, orderItemImageUrlResolver));
    }

    @GetMapping("/{orderId}/items/{orderItemId}/image")
    public ResponseEntity<InputStreamResource> getOrderItemImage(@AuthenticationPrincipal Jwt jwt,
                                                                 @PathVariable UUID orderId,
                                                                 @PathVariable UUID orderItemId) {
        OrderItemImageContent image = orderItemImageService.getCustomerOrderItemImage(
                currentUserId(jwt),
                orderId,
                orderItemId
        );

        return imageResponse(image);
    }

    private ResponseEntity<InputStreamResource> imageResponse(OrderItemImageContent image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.contentLength())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(new InputStreamResource(image.content()));
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
