package com.lul.shop.ordering.infrastructure.cart;

import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.ordering.application.port.CheckoutCartClient;
import com.lul.shop.ordering.application.port.CheckoutCartItemSnapshot;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CartCheckoutAdapter implements CheckoutCartClient {

    private final CartService cartService;

    public CartCheckoutAdapter(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public CheckoutCartSnapshot getCartForCheckout(UUID userId) {
        CartResult cartResult = cartService.getCart(userId);

        return new CheckoutCartSnapshot(
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

    private CheckoutCartItemSnapshot toSnapshot(CartItemResult item) {
        return new CheckoutCartItemSnapshot(
                item.productId(),
                item.quantity()
        );
    }
}