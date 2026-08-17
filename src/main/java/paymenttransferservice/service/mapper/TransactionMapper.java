package paymenttransferservice.service.mapper;

import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.Transaction;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.dto.TransactionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.Currency;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TransactionMapper {

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "timestamp", source = "createdAt")
    @Mapping(target = "sourceAccountId", source = "sourceAccount.id")
    @Mapping(target = "destinationAccountId", source = "destinationAccount.id")
    TransactionResponse toResponse(Transaction transaction);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "failureReason", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "currency", source = "request.currency")
    @Mapping(target = "sourceAccount", source = "sourceAccount")
    @Mapping(target = "destinationAccount", source = "destinationAccount")
    Transaction toEntity(PaymentTransferRequest request, Account sourceAccount, Account destinationAccount);

    default Currency toCurrency(String currencyCode) {
        return currencyCode == null ? null : Currency.getInstance(currencyCode);
    }

    default String toCurrencyCode(Currency currency) {
        return currency == null ? null : currency.getCurrencyCode();
    }
}
