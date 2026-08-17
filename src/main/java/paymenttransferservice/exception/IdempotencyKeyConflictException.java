package paymenttransferservice.exception;

public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(String.format("Idempotency key '%s' was already used with different transfer details", idempotencyKey));
    }
}
