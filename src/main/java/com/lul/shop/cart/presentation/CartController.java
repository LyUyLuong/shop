package com.lul.shop.cart.presentation;

import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.AddCartItemCommand;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.cart.application.dto.UpdateCartItemCommand;
import com.lul.shop.cart.presentation.dto.request.AddCartItemRequest;
import com.lul.shop.cart.presentation.dto.request.UpdateCartItemRequest;
import com.lul.shop.cart.presentation.dto.response.CartResponse;
import com.lul.shop.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ApiResponse<CartResponse> getCart(@AuthenticationPrincipal Jwt jwt) {
        CartResult result = cartService.getCart(currentUserId(jwt));

        return ApiResponse.ok(CartResponse.from(result));
    }

    @PostMapping("/items")
    public ApiResponse<CartResponse> addItem(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody AddCartItemRequest request) {
        AddCartItemCommand command = new AddCartItemCommand(
                currentUserId(jwt),
                request.productId(),
                request.quantity()
        );

        CartResult result = cartService.addItem(command);

        return ApiResponse.ok(CartResponse.from(result));
    }

    @PutMapping("/items/{itemId}")
    public ApiResponse<CartResponse> updateItem(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID itemId,
                                                @Valid @RequestBody UpdateCartItemRequest request) {
        UpdateCartItemCommand command = new UpdateCartItemCommand(
                currentUserId(jwt),
                itemId,
                request.quantity()
        );

        CartResult result = cartService.updateItem(command);

        return ApiResponse.ok(CartResponse.from(result));
    }

    @DeleteMapping("/items/{itemId}")
    public ApiResponse<CartResponse> removeItem(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID itemId) {
        CartResult result = cartService.removeItem(currentUserId(jwt), itemId);

        return ApiResponse.ok(CartResponse.from(result));
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}