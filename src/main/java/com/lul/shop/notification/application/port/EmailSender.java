package com.lul.shop.notification.application.port;

import com.lul.shop.notification.application.dto.OrderConfirmationEmail;

public interface EmailSender {

    void sendOrderConfirmation(OrderConfirmationEmail email);
}