package com.lul.shop.ordering.infrastructure.cart;

import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.ordering.application.port.CartCheckoutClient;
import com.lul.shop.ordering.application.port.CartItemSnapshot;
import com.lul.shop.ordering.application.port.CartSnapshot;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CartCheckoutClientAdapter implements CartCheckoutClient {

    private final CartService cartService;

    public CartCheckoutClientAdapter(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public CartSnapshot getCart(UUID userId) {
        CartResult cartResult = cartService.getCart(userId);

        return new CartSnapshot(
                cartResult.id(),
                cartResult.userId(),
                cartResult.items()
                        .stream()
                        .map(this::toSnapshot)
                        .toList()
        );
    }

    @Override
    public void clearCart(UUID userId) {
        cartService.clearCart(userId);
    }

    private CartItemSnapshot toSnapshot(CartItemResult item) {
        return new CartItemSnapshot(
                item.productId(),
                item.quantity()
        );
    }
}