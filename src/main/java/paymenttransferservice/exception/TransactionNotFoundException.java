package paymenttransferservice.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID transactionId) {
        super(String.format("Transaction with ID %s not found", transactionId));
    }
}
