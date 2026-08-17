package paymenttransferservice.service;

import lombok.RequiredArgsConstructor;
import paymenttransferservice.domain.Account;
import paymenttransferservice.domain.Transaction;
import paymenttransferservice.domain.TransactionStatus;
import paymenttransferservice.dto.PaymentTransferRequest;
import paymenttransferservice.repository.TransactionRepository;
import paymenttransferservice.service.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionLifecycleService {

    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction createPending(PaymentTransferRequest request, Account source, Account destination) {
        Transaction transaction = transactionMapper.toEntity(request, source, destination);
        transaction.setStatus(TransactionStatus.PENDING);
        return transactionRepository.save(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID transactionId, String reason) {
        transactionRepository.findById(transactionId).ifPresent(transaction -> {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(truncate(reason));
            transaction.setIdempotencyKey(null);
        });
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.substring(0, Math.min(reason.length(), MAX_FAILURE_REASON_LENGTH));
    }
}
