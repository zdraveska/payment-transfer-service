package paymenttransferservice.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import paymenttransferservice.dto.TransactionResponse;
import paymenttransferservice.service.PaymentTransferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountTransactionController {

    private final PaymentTransferService paymentTransferService;

    @GetMapping("/{accountId}/transactions")
    public List<TransactionResponse> getTransactionsByAccountId(@PathVariable UUID accountId) {
        log.info("Fetching all transactions for account: {}", accountId);
        return paymentTransferService.getTransactionsByAccountId(accountId);
    }
}
