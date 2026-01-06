package com.lingxiao.payment.api;

import com.lingxiao.payment.api.dto.SucceedPaymentRequest;
import com.lingxiao.payment.api.dto.PaymentResponse;
import com.lingxiao.payment.application.PaymentService;
import com.lingxiao.payment.domain.Payment;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/succeed")
    public ResponseEntity<PaymentResponse> succeedPayment(@Valid @RequestBody SucceedPaymentRequest request) {
        Payment payment = paymentService.succeedPayment(
                request.orderId(),
                request.amountCents(),
                request.currency()
        );
        return ResponseEntity.ok(new PaymentResponse(
                payment.paymentId(),
                payment.orderId(),
                payment.status(),
                payment.amountCents(),
                payment.currency(),
                payment.createdAt()
        ));
    }
}

