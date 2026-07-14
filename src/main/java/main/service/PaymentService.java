package main.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import main.application.states.TransactionStatus;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.dto.request.PaymentRequest;
import main.dto.response.PaymentResponse;
import main.dto.response.TransactionResponse;
import main.exception.InsufficientBalanceException;
import main.exception.ResourceNotFoundException;
import main.queue.TransactionQueue;
import main.queue.TransactionQueue.TransactionQueueFullException;
import main.repository.TransactionRepository;
import main.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/*
    Handles payment submission: validates, persists, and enqueues for async fraud analysis.

    Why enqueue instead of analyze inline?
    Calling fraudDetectionEngine.analyze() directly on the HTTP thread means 1,000 concurrent
    payments block 1,000 threads. The queue decouples submission latency from analysis latency:
    callers get PENDING in < 50 ms regardless of fraud engine load.
    The fraud engine runs on a fixed-size worker pool (TransactionWorkerPool).

    Note: userId is received as a parameter (extracted from the JWT by the controller),
    not from the request body — callers cannot forge their identity by supplying a different ID.
*/
@Service
@Slf4j
public class PaymentService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionQueue transactionQueue;
    private final TransactionMapper transactionMapper;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTemplate;

    public PaymentService(
            UserRepository userRepository,
            TransactionRepository transactionRepository,
            TransactionQueue transactionQueue,
            TransactionMapper transactionMapper,
            MeterRegistry meterRegistry,
            @Qualifier("requiresNewTransactionTemplate") TransactionTemplate requiresNewTemplate) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.transactionQueue = transactionQueue;
        this.transactionMapper = transactionMapper;
        this.meterRegistry = meterRegistry;
        this.requiresNewTemplate = requiresNewTemplate;
    }

    @Transactional
    public PaymentResponse submitPayment(String userId, PaymentRequest request) {
        User sender = userRepository.findById(userId)
            .orElseThrow(() -> ResourceNotFoundException.forUser(userId));

        if (!sender.isActive()) {
            throw new IllegalStateException("User account is inactive: " + userId);
        }
        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException(sender.getId(), request.getAmount(), sender.getBalance());
        }

        boolean isP2P = request.getReceiverId() != null;
        boolean hasMerchant = request.getMerchant() != null && !request.getMerchant().isBlank();

        if (isP2P && hasMerchant) {
            throw new IllegalArgumentException("Cannot specify both receiverId and merchant");
        }
        if (!isP2P && !hasMerchant) {
            throw new IllegalArgumentException("Either receiverId (P2P) or merchant must be provided");
        }

        User receiver = null;
        if (isP2P) {
            receiver = userRepository.findById(request.getReceiverId())
                .orElseThrow(() -> ResourceNotFoundException.forUser(request.getReceiverId()));
        }

        Transaction transaction = Transaction.builder()
            .sender(sender)
            .receiver(receiver)
            .amount(request.getAmount())
            .merchant(isP2P ? null : request.getMerchant())
            .location(request.getLocation())
            .country(request.getCountry())
            .ipAddress(request.getIpAddress())
            .deviceFingerprint(request.getDeviceFingerprint())
            .status(TransactionStatus.PENDING)
            .build();

        transaction = transactionRepository.save(transaction);
        enqueueAfterCommit(transaction);

        meterRegistry.counter("payment.submitted",
            "type", isP2P ? "p2p" : "merchant").increment();

        log.info("Payment submitted: txn={}, sender={}, amount={}, {}",
            transaction.getId(), sender.getId(), request.getAmount(),
            isP2P ? "receiver=" + request.getReceiverId() : "merchant=" + request.getMerchant());

        return PaymentResponse.builder()
            .transactionId(transaction.getId())
            .status(TransactionStatus.PENDING)
            .amount(request.getAmount())
            .merchant(request.getMerchant())
            .fraudScore(0.0)
            .message("Payment submitted and queued for fraud analysis.")
            .submittedAt(Instant.now())
            .build();
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> ResourceNotFoundException.forTransaction(transactionId));
        return transactionMapper.toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getUserTransactions(String userId, Pageable pageable) {
        userRepository.findById(userId)
            .orElseThrow(() -> ResourceNotFoundException.forUser(userId));
        return transactionRepository.findBySenderId(userId, pageable)
            .map(transactionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getFlaggedTransactions(Pageable pageable) {
        return transactionRepository.findFlaggedTransactions(
                List.of(TransactionStatus.FLAGGED_FOR_REVIEW, TransactionStatus.FRAUD_BLOCKED), pageable)
            .map(transactionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getAllTransactions(Pageable pageable) {
        return transactionRepository.findAll(pageable)
            .map(transactionMapper::toResponse);
    }

    private void enqueueAfterCommit(Transaction transaction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            transactionQueue.enqueue(transaction);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    transactionQueue.enqueue(transaction);
                } catch (TransactionQueueFullException e) {
                    // DB commit already succeeded but queue is at capacity.
                    // Mark the transaction DECLINED in a new transaction so it doesn't
                    // stay stuck at PENDING with no worker ever consuming it.
                    log.warn("Queue full after commit — declining txn {}", transaction.getId());
                    requiresNewTemplate.executeWithoutResult(status -> {
                        transactionRepository.findById(transaction.getId()).ifPresent(t -> {
                            t.setStatus(TransactionStatus.DECLINED);
                            t.setDeclineReason("Payment gateway at capacity — please retry");
                            transactionRepository.save(t);
                        });
                    });
                }
            }
        });
    }
}