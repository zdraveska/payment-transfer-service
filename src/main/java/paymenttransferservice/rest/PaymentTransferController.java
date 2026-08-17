package paymenttransferservice.rest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.dto.TransactionResponse;
import paymenttransferservice.service.PaymentTransferService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payment-transfers")
@RequiredArgsConstructor
public class PaymentTransferController {

    private final PaymentTransferService paymentTransferService;

    @GetMapping("/{transactionId}")
    public TransactionResponse getTransaction(@PathVariable UUID transactionId) {
        log.info("Fetching transaction with ID: {}", transactionId);
        return paymentTransferService.getTransaction(transactionId);
    }

    @PostMapping("/initiate")
    public TransactionResponse initiatePaymentTransfer(@Valid @RequestBody PaymentTransferRequest paymentTransferRequest) {
        log.info("Initiating transfer from account {} to account {} with amount={} {}",
                paymentTransferRequest.getSourceAccountId(), paymentTransferRequest.getDestinationAccountId(),
                paymentTransferRequest.getAmount(), paymentTransferRequest.getCurrency());
        return paymentTransferService.initiatePaymentTransfer(paymentTransferRequest);
    }

}

