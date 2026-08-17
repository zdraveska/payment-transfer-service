package paymenttransferservice.service;

import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.Transaction;
import paymenttransferservice.domain.TransactionStatus;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.dto.TransactionResponse;
import paymenttransferservice.exception.*;
import paymenttransferservice.repository.AccountRepository;
import paymenttransferservice.repository.TransactionRepository;
import paymenttransferservice.service.mapper.TransactionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import paymenttransferservice.service.mapper.TransactionMapperImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentTransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionLifecycleService transactionLifecycleService;

    private final TransactionMapper transactionMapper = new TransactionMapperImpl();

    private PaymentTransferService paymentTransferService;

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final BigDecimal MAX_TRANSFER_AMOUNT = new BigDecimal("10000.00");

    private UUID sourceId;
    private UUID destinationId;
    private Account sourceAccount;
    private Account destinationAccount;

    @BeforeEach
    void setUp() {
        paymentTransferService = new PaymentTransferService(accountRepository, transactionRepository, transactionLifecycleService, transactionMapper);
        ReflectionTestUtils.setField(paymentTransferService, "maxTransferAmount", MAX_TRANSFER_AMOUNT);

        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();

        sourceAccount = newAccount(sourceId, new BigDecimal("500.00"), EUR);
        destinationAccount = newAccount(destinationId, new BigDecimal("100.00"), EUR);
    }

    @Test
    void shouldInitiatePaymentTransfer() {
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("150.00"), null);
        Transaction pending = newTransaction(sourceAccount, destinationAccount, new BigDecimal("150.00"), null, TransactionStatus.PENDING);

        when(transactionLifecycleService.createPending(request, sourceAccount, destinationAccount)).thenReturn(pending);
        mockLockedAccounts(sourceAccount, destinationAccount);
        when(transactionRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = paymentTransferService.initiatePaymentTransfer(request);

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("350.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("250.00");
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.getSourceAccountId()).isEqualTo(sourceId);
        assertThat(response.getDestinationAccountId()).isEqualTo(destinationId);
        assertThat(response.getAmount()).isEqualByComparingTo("150.00");

        verify(accountRepository).save(sourceAccount);
        verify(accountRepository).save(destinationAccount);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(transactionLifecycleService, never()).markFailed(any(), any());
    }

    @Test
    void shouldThrowInvalidTransferException_whenSourceAndDestinationAreSame() {
        PaymentTransferRequest request = newRequest(sourceId, sourceId, new BigDecimal("10.00"), null);

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(InvalidTransferException.class);

        verifyNoInteractions(accountRepository);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(transactionLifecycleService);
    }

    @Test
    void shouldThrowTransferLimitExceededException_whenAmountExceedsMaxTransferAmount() {
        BigDecimal amount = new BigDecimal("10000.01");

        PaymentTransferRequest request = newRequest(sourceId, destinationId, amount, null);
        Transaction pending = newTransaction(sourceAccount, destinationAccount, amount, null, TransactionStatus.PENDING);
        when(transactionLifecycleService.createPending(request, sourceAccount, destinationAccount)).thenReturn(pending);
        mockLockedAccounts(sourceAccount, destinationAccount);

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(TransferLimitExceededException.class);

        verify(accountRepository, never()).save(any());
        verify(transactionLifecycleService).markFailed(eq(pending.getId()), anyString());
    }

    @Test
    void shouldThrowInsufficientFundsException_whenSourceAccountHasInsufficientBalance() {
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("999.00"), null);
        Transaction pending = newTransaction(sourceAccount, destinationAccount, new BigDecimal("999.00"), null, TransactionStatus.PENDING);
        when(transactionLifecycleService.createPending(request, sourceAccount, destinationAccount)).thenReturn(pending);
        mockLockedAccounts(sourceAccount, destinationAccount);

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(transactionLifecycleService).markFailed(eq(pending.getId()), anyString());
    }

    @Test
    void shouldThrowInvalidTransferException_whenCurrencyMismatch() {
        Account usdDestination = newAccount(destinationId, new BigDecimal("100.00"), Currency.getInstance("USD"));
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("50.00"), null);
        Transaction pending = newTransaction(sourceAccount, usdDestination, new BigDecimal("50.00"), null, TransactionStatus.PENDING);

        when(transactionLifecycleService.createPending(request, sourceAccount, usdDestination)).thenReturn(pending);
        mockLockedAccounts(sourceAccount, usdDestination);

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(InvalidTransferException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void shouldThrowAccountNotFoundException_whenSourceAccountNotFound() {
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("50.00"), null);

        UUID firstLockId = sourceId.compareTo(destinationId) < 0 ? sourceId : destinationId;
        UUID secondLockId = sourceId.compareTo(destinationId) < 0 ? destinationId : sourceId;

        when(accountRepository.findByIdForUpdate(firstLockId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.findByIdForUpdate(secondLockId)).thenReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(transactionLifecycleService);
    }

    @Test
    void shouldReturnExistingTransaction_whenIdempotencyKeyExistsAndRequestIsIdentical() {
        String idempotencyKey = "retry-key-123";
        BigDecimal amount = new BigDecimal("50.00");

        PaymentTransferRequest request = newRequest(sourceId, destinationId, amount, idempotencyKey);
        Transaction existingTransaction = newTransaction(sourceAccount, destinationAccount, amount, idempotencyKey,
                TransactionStatus.SUCCESS);
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTransaction));

        TransactionResponse response = paymentTransferService.initiatePaymentTransfer(request);
        assertThat(response.getTransactionId()).isEqualTo(existingTransaction.getId());

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionLifecycleService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldThrowIdempotencyKeyConflictException_whenKeyReusedWithDifferentRequestDetails() {
        String idempotencyKey = "reused-key";

        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("100.00"), idempotencyKey);
        Transaction existingTransaction = newTransaction(sourceAccount, destinationAccount, new BigDecimal("50.00"),
                idempotencyKey, TransactionStatus.SUCCESS);
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTransaction));

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionLifecycleService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldRecoverByReturningExistingTransaction_whenConcurrentDuplicateInsertOccurs() {
        String idempotencyKey = "race-key-456";
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("50.00"), idempotencyKey);
        Transaction winningTransaction = newTransaction(sourceAccount, destinationAccount, new BigDecimal("50.00"), idempotencyKey, TransactionStatus.SUCCESS);

        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningTransaction));
        mockLockedAccounts(sourceAccount, destinationAccount);
        when(transactionLifecycleService.createPending(request, sourceAccount, destinationAccount))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        TransactionResponse response = paymentTransferService.initiatePaymentTransfer(request);
        assertThat(response.getTransactionId()).isEqualTo(winningTransaction.getId());

        verify(accountRepository, never()).save(any());
    }

    @Test
    void shouldThrowDataIntegrityViolationException_whenDuplicateInsertButNoExistingRowFound() {
        String idempotencyKey = "edge-case-key";
        PaymentTransferRequest request = newRequest(sourceId, destinationId, new BigDecimal("50.00"), idempotencyKey);

        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        mockLockedAccounts(sourceAccount, destinationAccount);
        when(transactionLifecycleService.createPending(request, sourceAccount, destinationAccount))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> paymentTransferService.initiatePaymentTransfer(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldReturnTransaction() {
        Transaction transaction = newTransaction(sourceAccount, destinationAccount, new BigDecimal("50.00"), null, TransactionStatus.SUCCESS);
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        TransactionResponse response = paymentTransferService.getTransaction(transaction.getId());
        assertThat(response.getTransactionId()).isEqualTo(transaction.getId());
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void shouldThrowTransactionNotFoundException_whenTransactionNotFound() {
        UUID missingId = UUID.randomUUID();
        when(transactionRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentTransferService.getTransaction(missingId))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void shouldReturnMappedResponses_whenGetTransactionsByAccountId() {
        Transaction transaction1 = newTransaction(sourceAccount, destinationAccount, new BigDecimal("10.00"), null, TransactionStatus.SUCCESS);
        Transaction secondTransaction = newTransaction(destinationAccount, sourceAccount, new BigDecimal("20.00"), null, TransactionStatus.SUCCESS);

        when(accountRepository.existsById(sourceId)).thenReturn(true);
        when(transactionRepository.findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(sourceId, sourceId))
                .thenReturn(List.of(secondTransaction, transaction1));

        List<TransactionResponse> responses = paymentTransferService.getTransactionsByAccountId(sourceId);
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getTransactionId()).isEqualTo(secondTransaction.getId());
        assertThat(responses.get(1).getTransactionId()).isEqualTo(transaction1.getId());
    }

    private void mockLockedAccounts(Account source, Account destination) {
        UUID firstLockId = source.getId().compareTo(destination.getId()) < 0 ? source.getId() : destination.getId();
        UUID secondLockId = source.getId().compareTo(destination.getId()) < 0 ? destination.getId() : source.getId();

        Account first = source.getId().equals(firstLockId) ? source : destination;
        Account second = source.getId().equals(firstLockId) ? destination : source;

        when(accountRepository.findByIdForUpdate(firstLockId)).thenReturn(Optional.of(first));
        when(accountRepository.findByIdForUpdate(secondLockId)).thenReturn(Optional.of(second));
    }

    private Account newAccount(UUID id, BigDecimal balance, Currency currency) {
        Account account = new Account();
        account.setId(id);
        account.setBalance(balance);
        account.setCurrency(currency);
        return account;
    }

    private Transaction newTransaction(Account sourceAccount, Account destinationAccount, BigDecimal amount,
                                       String idempotencyKey, TransactionStatus status) {
        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setSourceAccount(sourceAccount);
        transaction.setDestinationAccount(destinationAccount);
        transaction.setAmount(amount);
        transaction.setCurrency(EUR);
        transaction.setStatus(status);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setCreatedAt(Instant.now());
        return transaction;
    }

    private PaymentTransferRequest newRequest(UUID sourceId, UUID destinationId, BigDecimal amount, String idempotencyKey) {
        PaymentTransferRequest request = new PaymentTransferRequest();
        request.setSourceAccountId(sourceId);
        request.setDestinationAccountId(destinationId);
        request.setAmount(amount);
        request.setCurrency(EUR.getCurrencyCode());
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }
}
