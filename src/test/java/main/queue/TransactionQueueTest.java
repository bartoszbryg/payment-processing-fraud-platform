package main.queue;

import main.databaseModel.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionQueueTest {

    TransactionQueue queue;

    @BeforeEach
    void setup() {
        queue = new TransactionQueue(100); // standard capacity
    }

    private Transaction transaction(String id) {
        Transaction t = new Transaction();
        t.setId(id);
        return t;
    }

    // enqueue / size 

    @Nested
    class EnqueueTests {

        @Test
        void enqueue_incrementsSize() {
            queue.enqueue(transaction("txn-001"));

            assertEquals(1, queue.size());
        }

        @Test
        void enqueueMultiple_sizeMatchesCount() {
            queue.enqueue(transaction("txn-001"));
            queue.enqueue(transaction("txn-002"));
            queue.enqueue(transaction("txn-003"));

            assertEquals(3, queue.size());
        }

        @Test
        void emptyQueue_sizeIsZero() {
            assertEquals(0, queue.size());
        }

        @Test
        void queueFull_throwsTransactionQueueFullException() {
            TransactionQueue tiny = new TransactionQueue(1);
            tiny.enqueue(transaction("txn-001")); // fills the queue

            assertThrows(TransactionQueue.TransactionQueueFullException.class,
                () -> tiny.enqueue(transaction("txn-002")));
        }

        @Test
        void transactionQueueFullException_hasMessage() {
            TransactionQueue tiny = new TransactionQueue(1);
            tiny.enqueue(transaction("txn-001"));

            TransactionQueue.TransactionQueueFullException ex = assertThrows(
                TransactionQueue.TransactionQueueFullException.class,
                () -> tiny.enqueue(transaction("txn-002")));

            assertNotNull(ex.getMessage());
            assertFalse(ex.getMessage().isBlank());
        }
    }

    // take

    @Nested
    class TakeTests {

        @Test
        void take_returnsEnqueuedTransaction() throws InterruptedException {
            Transaction txn = transaction("txn-001");
            queue.enqueue(txn);

            String taken = queue.take();

            assertEquals("txn-001", taken);
        }

        @Test
        void take_decrementsSize() throws InterruptedException {
            queue.enqueue(transaction("txn-001"));
            queue.enqueue(transaction("txn-002"));

            queue.take();

            assertEquals(1, queue.size());
        }

        @Test
        void take_fifo_order() throws InterruptedException {
            queue.enqueue(transaction("first"));
            queue.enqueue(transaction("second"));
            queue.enqueue(transaction("third"));

            assertEquals("first", queue.take());
            assertEquals("second", queue.take());
            assertEquals("third", queue.take());
        }
    }
}
