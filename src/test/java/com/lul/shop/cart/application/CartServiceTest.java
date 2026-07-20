package com.lul.shop.cart.application;

import com.lul.shop.cart.application.dto.AddCartItemCommand;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.cart.application.dto.UpdateCartItemCommand;
import com.lul.shop.cart.application.port.ProductAvailabilityClient;
import com.lul.shop.cart.application.port.ProductAvailabilitySnapshot;
import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartItem;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

public class CartServiceTest {


    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CART_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ITEM_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PRODUCT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");


    @Test
    void shouldCreateCartWhenGetCartDoesNotExist(){

        FakeCartRepository cartRepository = new FakeCartRepository();
        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        CartService service = new CartService(cartRepository, productClient);

        CartResult result = service.getCart(USER_ID);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.items()).isEmpty();
        assertThat(cartRepository.savedCarts).hasSize(1);
        assertThat(result.version()).isZero();

    }


    @Test
    void shouldAddNewItemWhenProductAvailableAndStockEnough() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        productClient.givenAvailableProduct(PRODUCT_ID,10);

        CartService service = new CartService(cartRepository,productClient);

        CartResult result = service.addItem(new AddCartItemCommand(USER_ID,PRODUCT_ID,2));

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.items().get(0).quantity()).isEqualTo(2);

        assertThat(cartRepository.savedCarts).hasSize(1);
        assertThat(productClient.lookupCalls).containsExactly(PRODUCT_ID);

    }

    @Test
    void shouldIncreaseQuantityWhenAddSameProductAgain(){
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        productClient.givenAvailableProduct(PRODUCT_ID, 5);

        CartService service = new CartService(cartRepository, productClient);

        CartResult result = service.addItem(new AddCartItemCommand(USER_ID, PRODUCT_ID, 3));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(ITEM_ID);
        assertThat(result.items().get(0).productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.items().get(0).quantity()).isEqualTo(5);
        assertThat(result.version()).isEqualTo(7L);

        assertThat(cartRepository.savedCarts).hasSize(1);

    }


    @Test
    void shouldThrowInsufficientStockWhenAddWouldExceedStock() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(3));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        productClient.givenAvailableProduct(PRODUCT_ID,4);

        CartService service = new CartService(cartRepository,productClient);


        assertThatThrownBy(() -> service.addItem(new AddCartItemCommand(USER_ID,PRODUCT_ID,2)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CartErrorCode.INSUFFICIENT_STOCK)
                );
        assertThat(cartRepository.savedCarts).isEmpty();

    }


    @Test
    void shouldThrowProductNotAvailableWhenAddProductNotFound() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();

        CartService service = new CartService(cartRepository, productClient);

        assertThatThrownBy(() -> service.addItem(new AddCartItemCommand(USER_ID, PRODUCT_ID, 1)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CartErrorCode.PRODUCT_NOT_AVAILABLE)
                );

        assertThat(cartRepository.savedCarts).isEmpty();
    }

    @Test
    void shouldUpdateItemQuantityWhenStockEnough() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        productClient.givenAvailableProduct(PRODUCT_ID, 10);

        CartService service = new CartService(cartRepository, productClient);

        CartResult result = service.updateItem(new UpdateCartItemCommand(USER_ID, ITEM_ID, 7));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(ITEM_ID);
        assertThat(result.items().get(0).quantity()).isEqualTo(7);

        assertThat(cartRepository.savedCarts).hasSize(1);
        assertThat(productClient.lookupCalls).containsExactly(PRODUCT_ID);
    }

    @Test
    void shouldThrowCartItemNotFoundWhenUpdateMissingItem() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();

        CartService service = new CartService(cartRepository, productClient);

        UUID missingItemId = UUID.fromString("55555555-5555-4555-8555-555555555555");

        assertThatThrownBy(() -> service.updateItem(new UpdateCartItemCommand(USER_ID, missingItemId, 3)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND)
                );

        assertThat(cartRepository.savedCarts).isEmpty();
        assertThat(productClient.lookupCalls).isEmpty();
    }

    @Test
    void shouldThrowInsufficientStockWhenUpdateQuantityExceedsStock() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();
        productClient.givenAvailableProduct(PRODUCT_ID, 5);

        CartService service = new CartService(cartRepository, productClient);

        assertThatThrownBy(() -> service.updateItem(new UpdateCartItemCommand(USER_ID, ITEM_ID, 6)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CartErrorCode.INSUFFICIENT_STOCK)
                );

        assertThat(cartRepository.savedCarts).isEmpty();
    }

    @Test
    void shouldRemoveItemWhenItemExists() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();

        CartService service = new CartService(cartRepository, productClient);

        CartResult result = service.removeItem(USER_ID, ITEM_ID);

        assertThat(result.items()).isEmpty();
        assertThat(cartRepository.savedCarts).hasSize(1);
    }

    @Test
    void shouldClearCartWhenCartExists() {
        FakeCartRepository cartRepository = new FakeCartRepository();
        cartRepository.givenCart(existingCartWithItem(2));

        FakeProductAvailabilityClient productClient = new FakeProductAvailabilityClient();

        CartService service = new CartService(cartRepository, productClient);

        service.clearCart(USER_ID);

        assertThat(cartRepository.savedCarts).hasSize(1);
        assertThat(cartRepository.savedCarts.get(0).getItems()).isEmpty();
    }



    private static Cart existingCartWithItem(int quantity) {
        return new Cart(
                CART_ID,
                USER_ID,
                7L,
                List.of(new CartItem(
                        ITEM_ID,
                        PRODUCT_ID,
                        quantity,
                        null,
                        null
                )),
                null,
                null
        );
    }






    private static class FakeCartRepository implements CartRepository{

        private final Map<UUID, Cart> cartsByUserId = new LinkedHashMap<>();
        private final List<Cart> savedCarts = new ArrayList<>();

        private void givenCart(Cart cart) {
            cartsByUserId.put(cart.getUserId(), cart);
        }


        @Override
        public Optional<Cart> findByUserId(UUID userId) {
            return Optional.ofNullable(cartsByUserId.get(userId));
        }

        @Override
        public Cart save(Cart cart) {
            cartsByUserId.put(cart.getUserId(),cart);
            savedCarts.add(cart);
            return cart;
        }
    }

    private static class FakeProductAvailabilityClient implements ProductAvailabilityClient{

        private final Map<UUID, ProductAvailabilitySnapshot> products = new HashMap<>();
        private final List<UUID> lookupCalls = new ArrayList<>();

        private void givenAvailableProduct(UUID productId, int stockQuantity) {
            products.put(productId, new ProductAvailabilitySnapshot(productId, stockQuantity));
        }

        @Override
        public Optional<ProductAvailabilitySnapshot> findAvailableProduct(UUID productId) {
            lookupCalls.add(productId);
            return Optional.ofNullable(products.get(productId));
        }
    }

}
