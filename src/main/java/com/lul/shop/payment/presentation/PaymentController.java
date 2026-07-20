package com.lul.shop.payment.presentation;

import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.presentation.dto.request.PayMockPaymentRequest;
import com.lul.shop.payment.presentation.dto.response.PaymentResponse;
import com.lul.shop.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/mock")
    public ApiResponse<PaymentResponse> payMock(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false
            )
            String idempotencyKey,
            @Valid @RequestBody PayMockPaymentRequest request
    ) {
        PayOrderCommand command = new PayOrderCommand(
                currentUserId(jwt),
                request.orderId(),
                idempotencyKey
        );

        PaymentResult result = paymentService.payMock(command);

        return ApiResponse.ok(PaymentResponse.from(result));
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentResponse> getPayment(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID paymentId) {
        PaymentResult result = paymentService.getPayment(currentUserId(jwt), paymentId);

        return ApiResponse.ok(PaymentResponse.from(result));
    }

    private UUID currentUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
