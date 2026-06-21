package com.lul.shop.cart.application;

import com.lul.shop.cart.application.dto.AddCartItemCommand;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.cart.application.dto.UpdateCartItemCommand;
import com.lul.shop.cart.application.port.CatalogProductClient;
import com.lul.shop.cart.application.port.CartProductSnapshot;
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
    private final CatalogProductClient catalogProductClient;

    public CartService(CartRepository cartRepository,
                       CatalogProductClient catalogProductClient) {
        this.cartRepository = cartRepository;
        this.catalogProductClient = catalogProductClient;
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

        CartProductSnapshot product = getActiveProductOrThrow(command.productId());

        Cart cart = cartRepository.findByUserId(command.userId())
                .orElseGet(() -> Cart.create(command.userId()));

        ensureStockIsEnoughAfterAdd(
                cart,
                command.productId(),
                command.quantity(),
                product.stockQuantity()
        );

        cart.addItem(command.productId(), command.quantity());

        Cart savedCart = cartRepository.save(cart);

        return toResult(savedCart);
    }

    @Transactional
    public CartResult updateItem(UpdateCartItemCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Cart cart = getExistingCartOrThrow(command.userId());

        CartItem existingItem = getExistingItemOrThrow(cart, command.itemId());

        CartProductSnapshot product = getActiveProductOrThrow(existingItem.getProductId());

        ensureStockIsEnough(command.quantity(), product.stockQuantity());

        cart.updateItemQuantity(command.itemId(), command.quantity());

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

    @Transactional
    public void clearCart(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        Cart cart = getExistingCartOrThrow(userId);

        cart.clear();

        cartRepository.save(cart);
    }

    private Cart getExistingCartOrThrow(UUID userId) {
        return cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(CartErrorCode.CART_NOT_FOUND));
    }

    private CartItem getExistingItemOrThrow(Cart cart, UUID itemId) {
        return cart.getItems()
                .stream()
                .filter(item -> item.hasId(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(CartErrorCode.CART_ITEM_NOT_FOUND));
    }

    private CartProductSnapshot getActiveProductOrThrow(UUID productId) {
        return catalogProductClient.findActiveProduct(productId)
                .orElseThrow(() -> new BusinessException(CartErrorCode.PRODUCT_NOT_AVAILABLE));
    }

    private int currentProductQuantity(Cart cart, UUID productId) {
        return cart.getItems()
                .stream()
                .filter(item -> item.hasProduct(productId))
                .mapToInt(CartItem::getQuantity)
                .findFirst()
                .orElse(0);
    }

    private void ensureStockIsEnough(int requestedQuantity, int stockQuantity) {
        if (requestedQuantity > stockQuantity) {
            throw new BusinessException(CartErrorCode.INSUFFICIENT_STOCK);
        }
    }

    private void ensureStockIsEnoughAfterAdd(Cart cart,
                                             UUID productId,
                                             int quantityToAdd,
                                             int stockQuantity) {
        int quantityAfterAdd = currentProductQuantity(cart, productId) + quantityToAdd;

        ensureStockIsEnough(quantityAfterAdd, stockQuantity);
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