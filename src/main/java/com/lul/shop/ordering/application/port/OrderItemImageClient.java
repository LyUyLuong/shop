package com.lul.shop.ordering.application.port;

import com.lul.shop.ordering.application.dto.OrderItemImageContent;

public interface OrderItemImageClient {

    OrderItemImageContent loadOrderItemImage(String imageKey);
}