package com.lul.shop.notification.infrastructure.email;

import com.lul.shop.notification.application.dto.OrderConfirmationEmail;
import com.lul.shop.notification.application.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendOrderConfirmation(OrderConfirmationEmail email) {
        log.info(
                "Order confirmation email sent: userId={}, orderId={}, paymentId={}",
                email.userId(),
                email.orderId(),
                email.paymentId()
        );
    }
}