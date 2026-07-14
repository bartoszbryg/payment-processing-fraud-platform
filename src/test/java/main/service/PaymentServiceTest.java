package main.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock UserRepository userRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock TransactionQueue transactionQueue;
    @Mock TransactionMapper transactionMapper;
    @Mock TransactionTemplate transactionTemplate;

    SimpleMeterRegistry meterRegistry;
    PaymentService paymentService;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentService(
            userRepository, transactionRepository, transactionQueue, transactionMapper, meterRegistry, transactionTemplate);
    }

    private User activeUser(String id, BigDecimal balance) {
        User u = User.builder()
            .name("Alice").email("alice@example.com")
            .phoneNumber("+14155550001")
            .balance(balance)
            .homeCountry("US").homeCity("New York")
            .build();
        u.setId(id);
        return u;
    }

    private PaymentRequest merchantRequest() {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(new BigDecimal("100.00"));
        req.setMerchant("Amazon");
        req.setLocation("New York");
        req.setCountry("US");
        return req;
    }

    private PaymentRequest p2pRequest(String receiverId) {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(new BigDecimal("50.00"));
        req.setReceiverId(receiverId);
        req.setLocation("New York");
        req.setCountry("US");
        return req;
    }

    private Transaction savedTransaction(String id, User sender) {
        Transaction t = Transaction.builder()
            .sender(sender)
            .amount(new BigDecimal("100.00"))
            .status(TransactionStatus.PENDING)
            .build();
        t.setId(id);
        return t;
    }

    // submitPayment

    @Nested
    class SubmitPaymentTests {

        @Test
        void merchantPayment_returnsPaymentResponse() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction saved = savedTransaction("txn-001", sender);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(transactionRepository.save(any())).thenReturn(saved);

            PaymentResponse response = paymentService.submitPayment("user-001", merchantRequest());

            assertEquals("txn-001", response.getTransactionId());
            assertEquals(TransactionStatus.PENDING, response.getStatus());
            assertEquals(new BigDecimal("100.00"), response.getAmount());
            assertEquals(0.0, response.getFraudScore(), 0.001);
            assertNotNull(response.getMessage());
        }

        @Test
        void merchantPayment_enqueuedForFraudAnalysis() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction saved = savedTransaction("txn-001", sender);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(transactionRepository.save(any())).thenReturn(saved);

            paymentService.submitPayment("user-001", merchantRequest());

            verify(transactionQueue).enqueue(saved);
        }

        @Test
        void p2pPayment_loadsReceiver() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            User receiver = activeUser("user-002", new BigDecimal("500.00"));
            Transaction saved = savedTransaction("txn-002", sender);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(userRepository.findById("user-002")).thenReturn(Optional.of(receiver));
            when(transactionRepository.save(any())).thenReturn(saved);

            PaymentResponse response = paymentService.submitPayment("user-001", p2pRequest("user-002"));

            assertNotNull(response.getTransactionId());
            verify(userRepository).findById("user-002");
        }

        @Test
        void inactiveUser_throws() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            sender.setActive(false);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));

            assertThrows(IllegalStateException.class,
                () -> paymentService.submitPayment("user-001", merchantRequest()));

            verify(transactionRepository, never()).save(any());
            verify(transactionQueue, never()).enqueue(any());
        }

        @Test
        void insufficientBalance_throws() {
            User sender = activeUser("user-001", new BigDecimal("50.00")); // less than 100.00
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));

            assertThrows(InsufficientBalanceException.class,
                () -> paymentService.submitPayment("user-001", merchantRequest()));

            verify(transactionRepository, never()).save(any());
        }

        @Test
        void userNotFound_throws() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> paymentService.submitPayment("ghost", merchantRequest()));
        }

        @Test
        void bothMerchantAndReceiverId_throws() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));

            PaymentRequest req = merchantRequest();
            req.setReceiverId("user-002"); // sets both

            assertThrows(IllegalArgumentException.class,
                () -> paymentService.submitPayment("user-001", req));

            verify(transactionRepository, never()).save(any());
        }

        @Test
        void neitherMerchantNorReceiverId_throws() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));

            PaymentRequest req = new PaymentRequest();
            req.setAmount(new BigDecimal("100.00"));
            req.setLocation("New York");

            assertThrows(IllegalArgumentException.class,
                () -> paymentService.submitPayment("user-001", req));
        }

        @Test
        void p2pReceiverNotFound_throws() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> paymentService.submitPayment("user-001", p2pRequest("ghost")));
        }

        @Test
        void savedTransaction_hasCorrectStatus() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            Transaction saved = savedTransaction("txn-001", sender);
            when(transactionRepository.save(captor.capture())).thenReturn(saved);

            paymentService.submitPayment("user-001", merchantRequest());

            assertEquals(TransactionStatus.PENDING, captor.getValue().getStatus());
        }

        @Test
        void queueFullAfterCommit_declinesTransactionSoItIsNotStranded() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction saved = savedTransaction("txn-001", sender);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(transactionRepository.save(any())).thenReturn(saved);
            doThrow(new TransactionQueueFullException()).when(transactionQueue).enqueue(saved);
            when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(saved));
            doAnswer(invocation -> {
                Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            TransactionSynchronizationManager.initSynchronization();
            try {
                paymentService.submitPayment("user-001", merchantRequest());

                TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertEquals(TransactionStatus.DECLINED, saved.getStatus());
            assertTrue(saved.getDeclineReason().contains("capacity"));
            verify(transactionRepository, times(2)).save(any(Transaction.class));
        }
    }

    // getTransaction

    @Nested
    class GetTransactionTests {

        @Test
        void getTransaction_found_returnsResponse() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction txn = savedTransaction("txn-001", sender);
            TransactionResponse response = TransactionResponse.builder()
                .id("txn-001").userId("user-001").build();
            when(transactionRepository.findById("txn-001")).thenReturn(Optional.of(txn));
            when(transactionMapper.toResponse(txn)).thenReturn(response);

            TransactionResponse result = paymentService.getTransaction("txn-001");

            assertEquals("txn-001", result.getId());
        }

        @Test
        void getTransaction_notFound_throws() {
            when(transactionRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> paymentService.getTransaction("ghost"));
        }
    }

    // getUserTransactions

    @Nested
    class GetUserTransactionsTests {

        @Test
        void getUserTransactions_userExists_returnsPage() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction txn = savedTransaction("txn-001", sender);
            TransactionResponse response = TransactionResponse.builder().id("txn-001").build();
            var pageable = PageRequest.of(0, 10);
            when(userRepository.findById("user-001")).thenReturn(Optional.of(sender));
            when(transactionRepository.findBySenderId("user-001", pageable))
                .thenReturn(new PageImpl<>(List.of(txn)));
            when(transactionMapper.toResponse(txn)).thenReturn(response);

            Page<TransactionResponse> result = paymentService.getUserTransactions("user-001", pageable);

            assertEquals(1, result.getContent().size());
        }

        @Test
        void getUserTransactions_userNotFound_throws() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                () -> paymentService.getUserTransactions("ghost", PageRequest.of(0, 10)));
        }
    }

    // getFlaggedTransactions

    @Nested
    class GetFlaggedTransactionsTests {

        @Test
        void getFlaggedTransactions_returnsMappedPage() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction txn = savedTransaction("txn-001", sender);
            TransactionResponse response = TransactionResponse.builder().id("txn-001").build();
            var pageable = PageRequest.of(0, 10);
            when(transactionRepository.findFlaggedTransactions(any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(txn)));
            when(transactionMapper.toResponse(txn)).thenReturn(response);

            Page<TransactionResponse> result = paymentService.getFlaggedTransactions(pageable);

            assertEquals(1, result.getContent().size());
        }
    }

    // getAllTransactions

    @Nested
    class GetAllTransactionsTests {

        @Test
        void getAllTransactions_returnsMappedPage() {
            User sender = activeUser("user-001", new BigDecimal("1000.00"));
            Transaction txn = savedTransaction("txn-001", sender);
            TransactionResponse response = TransactionResponse.builder().id("txn-001").build();
            var pageable = PageRequest.of(0, 10);
            when(transactionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(txn)));
            when(transactionMapper.toResponse(txn)).thenReturn(response);

            Page<TransactionResponse> result = paymentService.getAllTransactions(pageable);

            assertEquals(1, result.getContent().size());
        }

        @Test
        void getAllTransactions_empty_returnsEmpty() {
            var pageable = PageRequest.of(0, 10);
            when(transactionRepository.findAll(pageable)).thenReturn(Page.empty());

            Page<TransactionResponse> result = paymentService.getAllTransactions(pageable);

            assertTrue(result.getContent().isEmpty());
        }
    }
}
