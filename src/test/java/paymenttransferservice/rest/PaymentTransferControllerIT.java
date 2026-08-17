package paymenttransferservice.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.Transaction;
import paymenttransferservice.domain.TransactionStatus;
import paymenttransferservice.domain.User;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.repository.AccountRepository;
import paymenttransferservice.repository.TransactionRepository;
import paymenttransferservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static paymenttransferservice.rest.GlobalExceptionHandler.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentTransferControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String EUR = "EUR";
    private static final int THREADS = 20;
    private static final String INITIATE_URL = "/api/v1/payment-transfers/initiate";
    private static final String TRANSACTION_URL = "/api/v1/payment-transfers/{transactionId}";
    private static final String ACCOUNT_TRANSACTIONS_URL = "/api/v1/accounts/{accountId}/transactions";
    private static final String TRANSACTION_ID_FIELD = "transactionId";

    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User sourceOwner = createUser("Jane", "Doe");
        User destinationOwner = createUser("John", "Doe");
        sourceId = createAccount(sourceOwner, new BigDecimal("1000.00")).getId();
        destinationId = createAccount(destinationOwner, BigDecimal.ZERO).getId();
    }

    @Test
    void transfer_movesTheMoneyAndIsRetrievableAfterwards() throws Exception {
        String created = postTransfer(transferRequest(new BigDecimal("50.00"), UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_SUCCESS))
                .andExpect(jsonPath("$.amount").value(50.00))
                .andReturn().getResponse().getContentAsString();
        String transactionId = objectMapper.readTree(created).get(TRANSACTION_ID_FIELD).asText();

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("950.00");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("50.00");

        mockMvc.perform(get(TRANSACTION_URL, transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId))
                .andExpect(jsonPath("$.status").value(STATUS_SUCCESS));

        mockMvc.perform(get(TRANSACTION_URL, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TRANSACTION_NOT_FOUND));
    }

    @Test
    void transfer_isRejectedWhenTheRequestItselfIsInvalid() throws Exception {
        postTransfer(new PaymentTransferRequest())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR));

        postTransfer(transferRequest(sourceId, sourceId, new BigDecimal("10.00"), UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(INVALID_TRANSFER));

        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void transfer_resolvesTheCurrencyCodeRatherThanJustCheckingItsLength() throws Exception {
        PaymentTransferRequest unusable = transferRequest(new BigDecimal("10.00"), UUID.randomUUID().toString());
        unusable.setCurrency("ABC");
        postTransfer(unusable)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR));

        mockMvc.perform(post(INITIATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00,
                                 "currency":"eur","idempotencyKey":"%s"}""".formatted(
                                sourceId, destinationId, UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void transfer_againstAMissingAccount_is404AndRecordsNothing() throws Exception {
        UUID unknownSource = UUID.randomUUID();

        postTransfer(transferRequest(sourceId, UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ACCOUNT_NOT_FOUND));

        postTransfer(transferRequest(unknownSource, destinationId, new BigDecimal("10.00"), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ACCOUNT_NOT_FOUND));

        assertThat(transactionRepository.count()).isZero();
        mockMvc.perform(get(ACCOUNT_TRANSACTIONS_URL, unknownSource))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedTransfers_areRecordedAsFailedWithAReason() throws Exception {
        postTransfer(transferRequest(new BigDecimal("999999.00"), UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(TRANSFER_LIMIT_EXCEEDED));

        postTransfer(transferRequest(new BigDecimal("5000.00"), UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(INSUFFICIENT_FUNDS));

        List<Transaction> transactions = transactionsOf(sourceId);
        assertThat(transactions)
                .hasSize(2)
                .allSatisfy(transaction -> {
                    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
                    assertThat(transaction.getFailureReason()).isNotBlank();
        });
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void accountTransactions_areVisibleFromBothSidesOfTheTransfer() throws Exception {
        postTransfer(transferRequest(new BigDecimal("30.00"), UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get(ACCOUNT_TRANSACTIONS_URL, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceAccountId").value(sourceId.toString()))
                .andExpect(jsonPath("$[0].amount").value(30.00));

        mockMvc.perform(get(ACCOUNT_TRANSACTIONS_URL, destinationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        UUID quietAccountId = createAccount(createUser("Quiet", "User"), new BigDecimal("10.00")).getId();
        mockMvc.perform(get(ACCOUNT_TRANSACTIONS_URL, quietAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void replayingAKey_returnsTheOriginalTransferInsteadOfChargingTwice() throws Exception {
        PaymentTransferRequest request = transferRequest(new BigDecimal("40.00"), UUID.randomUUID().toString());

        String first = postTransfer(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String transactionId = objectMapper.readTree(first).get(TRANSACTION_ID_FIELD).asText();

        postTransfer(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId));

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("960.00");
    }

    @Test
    void reusingAKeyForADifferentTransfer_isRejectedAsConflict() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        postTransfer(transferRequest(new BigDecimal("10.00"), idempotencyKey))
                .andExpect(status().isOk());

        postTransfer(transferRequest(new BigDecimal("20.00"), idempotencyKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(IDEMPOTENCY_KEY_CONFLICT));

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("990.00");
    }

    @Test
    void concurrentTransfers_neverOverdrawSourceAccount() throws InterruptedException {
        BigDecimal transferAmount = new BigDecimal("100.00");
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        runConcurrently(THREADS, () -> {
            try {
                int status = postTransfer(transferRequest(transferAmount, UUID.randomUUID().toString()))
                        .andReturn().getResponse().getStatus();
                (status == 200 ? successCount : failureCount).incrementAndGet();
            } catch (Exception _) {
                failureCount.incrementAndGet();
            }
        });

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failureCount.get()).isEqualTo(10);
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void concurrentTransfers_withSameIdempotencyKey_onlyProcessOnce() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();
        BigDecimal transferAmount = new BigDecimal("50.00");

        runConcurrently(10, () -> {
            try {
                postTransfer(transferRequest(transferAmount, idempotencyKey));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("950.00");
        assertThat(transactionsOf(sourceId)).hasSize(1);
    }

    @Test
    void retryingAKeyWhoseTransferFailed_isAllowedToSucceed() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        // 5000 against a 1000 balance
        postTransfer(transferRequest(sourceId, destinationId, new BigDecimal("5000.00"), idempotencyKey))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(INSUFFICIENT_FUNDS));

        postTransfer(transferRequest(sourceId, destinationId, new BigDecimal("50.00"), idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_SUCCESS));

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("950.00");
        assertThat(transactionsOf(sourceId)).hasSize(2);
    }

    private ResultActions postTransfer(PaymentTransferRequest request) throws Exception {
        return mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private PaymentTransferRequest transferRequest(BigDecimal amount, String idempotencyKey) {
        return transferRequest(sourceId, destinationId, amount, idempotencyKey);
    }

    private PaymentTransferRequest transferRequest(UUID sourceAccountId, UUID destinationAccountId,
                                                   BigDecimal amount, String idempotencyKey) {
        PaymentTransferRequest request = new PaymentTransferRequest();
        request.setSourceAccountId(sourceAccountId);
        request.setDestinationAccountId(destinationAccountId);
        request.setAmount(amount);
        request.setCurrency(EUR);
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    private List<Transaction> transactionsOf(UUID accountId) {
        return transactionRepository
                .findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(accountId, accountId);
    }

    private void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    action.run();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private User createUser(String firstName, String lastName) {
        User user = new User();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUsername(firstName.toLowerCase() + "_" + lastName.toLowerCase() );
        user.setEmail(user.getUsername() + "@example.com");
        return userRepository.save(user);
    }

    private Account createAccount(User owner, BigDecimal balance) {
        Account account = new Account();
        account.setOwner(owner);
        account.setBalance(balance);
        account.setCurrency(Currency.getInstance(EUR));
        return accountRepository.save(account);
    }
}
