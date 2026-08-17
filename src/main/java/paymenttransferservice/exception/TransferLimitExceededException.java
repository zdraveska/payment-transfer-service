package paymenttransferservice.exception;

import java.math.BigDecimal;

public class TransferLimitExceededException extends RuntimeException {

    public TransferLimitExceededException(BigDecimal amount, BigDecimal maxAllowed) {
        super(String.format("Transfer amount %s exceeds the maximum allowed per-transfer limit of %s", amount, maxAllowed));
    }
}
