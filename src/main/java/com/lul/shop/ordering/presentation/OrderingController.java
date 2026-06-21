package com.lul.shop.ordering.presentation;

import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.presentation.dto.response.OrderResponse;
import com.lul.shop.shared.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderingController {

    private final OrderingService orderingService;

    public OrderingController(OrderingService orderingService) {
        this.orderingService = orderingService;
    }

    @PostMapping
    public ApiResponse<OrderResponse> placeOrder(@AuthenticationPrincipal Jwt jwt) {
        PlaceOrderCommand command = new PlaceOrderCommand(currentUserId(jwt));

        OrderResult result = orderingService.placeOrder(command);

        return ApiResponse.ok(OrderResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> getOrders(@AuthenticationPrincipal Jwt jwt) {
        List<OrderResponse> response = orderingService.getOrders(currentUserId(jwt))
                .stream()
                .map(OrderResponse::from)
                .toList();

        return ApiResponse.ok(response);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getOrder(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID orderId) {
        OrderResult result = orderingService.getOrder(currentUserId(jwt), orderId);

        return ApiResponse.ok(OrderResponse.from(result));
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}