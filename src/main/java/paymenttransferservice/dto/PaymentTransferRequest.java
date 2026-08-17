package paymenttransferservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import paymenttransferservice.dto.validation.ValidCurrencyCode;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class PaymentTransferRequest {

    @NotNull(message = "sourceAccountId is required")
    private UUID sourceAccountId;

    @NotNull(message = "destinationAccountId is required")
    private UUID destinationAccountId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "currency is required")
    @ValidCurrencyCode
    private String currency;

    @NotBlank(message = "idempotencyKey is required")
    @Size(max = 100, message = "idempotencyKey must be at most 100 characters")
    private String idempotencyKey;

    public void setCurrency(String currency) {
        this.currency = currency == null ? null : currency.trim().toUpperCase();
    }
}
