package paymenttransferservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal amount) {
        super(String.format("Insufficient funds for account %s: balance %s, attempted transfer amount %s", accountId, balance, amount));
    }
}

