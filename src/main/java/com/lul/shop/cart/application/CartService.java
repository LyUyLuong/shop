package com.lul.shop.cart.application;


import com.lul.shop.cart.application.dto.AddCartItemCommand;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.cart.application.dto.UpdateCartItemCommand;
import com.lul.shop.cart.application.port.CartProductCatalog;
import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartItem;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final CartProductCatalog cartProductCatalog;

    public CartService(CartRepository cartRepository,
                       CartProductCatalog cartProductCatalog) {
        this.cartRepository = cartRepository;
        this.cartProductCatalog = cartProductCatalog;
    }

    @Transactional
    public CartResult getCart(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.save(Cart.create(userId)));

        return toResult(cart);
    }

    @Transactional
    public CartResult addItem(AddCartItemCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        if(!cartProductCatalog.existsActiveProduct(command.productId())) {
            throw new BusinessException(CartErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        Cart cart = cartRepository.findByUserId(command.userId())
                .orElseGet(() -> Cart.create(command.userId()));

        cart.addItem(command.productId(), command.quantity());

        Cart savedCart = cartRepository.save(cart);

        return toResult(savedCart);

    }

    @Transactional
    public CartResult updateItem(UpdateCartItemCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Cart cart = getExistingCartOrThrow(command.userId());

        boolean updated = cart.updateItemQuantity(command.itemId(), command.quantity());

        if (!updated) {
            throw new BusinessException(CartErrorCode.CART_ITEM_NOT_FOUND);
        }

        Cart savedCart = cartRepository.save(cart);

        return toResult(savedCart);
    }

    @Transactional
    public CartResult removeItem(UUID userId, UUID itemId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");

        Cart cart = getExistingCartOrThrow(userId);

        boolean removed = cart.removeItem(itemId);

        if (!removed) {
            throw new BusinessException(CartErrorCode.CART_ITEM_NOT_FOUND);
        }

        Cart savedCart = cartRepository.save(cart);

        return toResult(savedCart);
    }

    private Cart getExistingCartOrThrow(UUID userId) {
        return cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(CartErrorCode.CART_NOT_FOUND));
    }

    private CartResult toResult(Cart cart) {
        return new CartResult(
                cart.getId(),
                cart.getUserId(),
                cart.getItems()
                        .stream()
                        .map(this::toItemResult)
                        .toList(),
                cart.getCreatedAt(),
                cart.getUpdatedAt()
        );
    }

    private CartItemResult toItemResult(CartItem item) {
        return new CartItemResult(
                item.getId(),
                item.getProductId(),
                item.getQuantity()
        );
    }

}
