package com.lul.shop.payment.presentation;

import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.application.dto.CollectCodCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.presentation.dto.response.PaymentResponse;
import com.lul.shop.shared.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
public class AdminCodCollectionController {

    private final PaymentService paymentService;

    public AdminCodCollectionController(
            PaymentService paymentService
    ) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{orderId}/cod-collection")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PaymentResponse> collectCod(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @RequestHeader(name = "Idempotency-Key")
            String idempotencyKey
    ) {
        CollectCodCommand command = new CollectCodCommand(
                currentUserId(jwt),
                orderId,
                idempotencyKey
        );

        PaymentResult result =
                paymentService.collectCod(command);

        return ApiResponse.ok(
                PaymentResponse.from(result)
        );
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}