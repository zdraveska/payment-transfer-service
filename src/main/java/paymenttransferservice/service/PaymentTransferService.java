package paymenttransferservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.Transaction;
import paymenttransferservice.domain.TransactionStatus;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.dto.TransactionResponse;
import paymenttransferservice.exception.*;
import paymenttransferservice.repository.AccountRepository;
import paymenttransferservice.repository.TransactionRepository;
import paymenttransferservice.service.mapper.TransactionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionLifecycleService transactionLifecycleService;
    private final TransactionMapper transactionMapper;

    @Value("${payment-transfer.max-transfer-amount}")
    private BigDecimal maxTransferAmount;

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(transactionMapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactionsByAccountId(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return transactionRepository
                .findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(accountId, accountId)
                .stream().map(transactionMapper::toResponse).toList();
    }

    @Transactional
    public TransactionResponse initiatePaymentTransfer(PaymentTransferRequest request) {
        return findExistingByIdempotencyKey(request)
                .orElseGet(() -> processNewTransfer(request));
    }

    private Optional<TransactionResponse> findExistingByIdempotencyKey(PaymentTransferRequest request) {
        if (request.getIdempotencyKey() == null) {
            return Optional.empty();
        }
        return transactionRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .map(existing -> {
                    if (!isSameTransfer(existing, request)) {
                        throw new IdempotencyKeyConflictException(request.getIdempotencyKey());
                    }
                    return transactionMapper.toResponse(existing);
                });
    }

    private TransactionResponse processNewTransfer(PaymentTransferRequest request) {
        validateSameAccount(request);

        AccountPair accounts = lockAccountsInOrder(request.getSourceAccountId(), request.getDestinationAccountId());
        Account source = accounts.source();
        Account destination = accounts.destination();

        Transaction pending;
        try {
            pending = transactionLifecycleService.createPending(request, source, destination);
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate transfer detected for idempotency key: {}", request.getIdempotencyKey());
            return findExistingByIdempotencyKey(request).orElseThrow(() -> e);
        }

        try {
            validateCurrenciesMatch(source.getCurrency(), destination.getCurrency(),
                    Currency.getInstance(request.getCurrency()));
            validateTransferLimit(request);
            applyBalanceChanges(source, destination, request.getAmount());

            Transaction completed = markSuccess(pending.getId());

            log.info("Payment transfer successful: {} -> {} of amount {} {}",
                    source.getId(), destination.getId(), request.getAmount(), request.getCurrency());
            return transactionMapper.toResponse(completed);
        } catch (InsufficientFundsException | TransferLimitExceededException | InvalidTransferException ex) {
            transactionLifecycleService.markFailed(pending.getId(), ex.getMessage());
            log.warn("Payment transfer failed: {} -> {} of amount {} {}. Reason: {}",
                    source.getId(), destination.getId(), request.getAmount(), request.getCurrency(), ex.getMessage());
            throw ex;
        }
    }

    private boolean isSameTransfer(Transaction existing, PaymentTransferRequest request) {
        return existing.getSourceAccount().getId().equals(request.getSourceAccountId())
                && existing.getDestinationAccount().getId().equals(request.getDestinationAccountId())
                && existing.getAmount().compareTo(request.getAmount()) == 0
                && existing.getCurrency().getCurrencyCode().equals(request.getCurrency());
    }

    private AccountPair lockAccountsInOrder(UUID sourceId, UUID destinationId) {
        // Always lock in the same order (by ID) so two transfers moving money in
        // opposite directions between the same pair of accounts can't deadlock.

        UUID firstLockId = sourceId.compareTo(destinationId) < 0 ? sourceId : destinationId;
        UUID secondLockId = sourceId.compareTo(destinationId) < 0 ? destinationId : sourceId;

        Account first = accountRepository.findByIdForUpdate(firstLockId).orElseThrow(() -> new AccountNotFoundException(firstLockId));
        Account second = accountRepository.findByIdForUpdate(secondLockId).orElseThrow(() -> new AccountNotFoundException(secondLockId));

        Account source = sourceId.equals(first.getId()) ? first : second;
        Account destination = sourceId.equals(first.getId()) ? second : first;

        return new AccountPair(source, destination);
    }

    private void validateSameAccount(PaymentTransferRequest request) {
        if (request.getSourceAccountId().equals(request.getDestinationAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must be different");
        }
    }

    private void validateTransferLimit(PaymentTransferRequest request) {
        if (request.getAmount().compareTo(maxTransferAmount) > 0) {
            throw new TransferLimitExceededException(request.getAmount(), maxTransferAmount);
        }
    }

    private void validateCurrenciesMatch(Currency sourceCurrency, Currency destinationCurrency, Currency requestCurrency) {
        if (!sourceCurrency.equals(requestCurrency) || !destinationCurrency.equals(requestCurrency)) {
            throw new InvalidTransferException("Currency mismatch between accounts and transfer request");
        }
    }

    private void applyBalanceChanges(Account source, Account destination, BigDecimal amount) {
        source.withdraw(amount);
        destination.deposit(amount);
        accountRepository.save(source);
        accountRepository.save(destination);
    }

    private Transaction markSuccess(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transaction.setStatus(TransactionStatus.SUCCESS);
        return transactionRepository.save(transaction);
    }

    private record AccountPair(Account source, Account destination) {}
}
