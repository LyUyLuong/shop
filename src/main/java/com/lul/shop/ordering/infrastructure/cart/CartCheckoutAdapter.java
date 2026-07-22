package com.lul.shop.ordering.infrastructure.cart;

import com.lul.shop.cart.application.CartErrorCode;
import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.port.CheckoutCartClient;
import com.lul.shop.ordering.application.port.CheckoutCartItemSnapshot;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CartCheckoutAdapter
        implements CheckoutCartClient {

    private final CartService cartService;

    public CartCheckoutAdapter(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public CheckoutCartSnapshot claimForCheckout(
            UUID userId,
            UUID cartId,
            long expectedVersion
    ) {
        try {
            CartResult result =
                    cartService.claimForCheckout(
                            userId,
                            cartId,
                            expectedVersion
                    );

            return new CheckoutCartSnapshot(
                    result.id(),
                    result.userId(),
                    result.version(),
                    result.items()
                            .stream()
                            .map(this::toSnapshot)
                            .toList()
            );
        } catch (BusinessException exception) {
            if (exception.getErrorCode()
                    == CartErrorCode.CART_CHECKOUT_CONFLICT) {
                throw new BusinessException(
                        OrderingErrorCode
                                .CART_CHECKOUT_CONFLICT
                );
            }

            throw exception;
        }
    }

    private CheckoutCartItemSnapshot toSnapshot(
            CartItemResult item
    ) {
        return new CheckoutCartItemSnapshot(
                item.productId(),
                item.quantity()
        );
    }
}