package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface OrderInventoryClient {

    boolean restoreStock(UUID productId, int quantity);
}