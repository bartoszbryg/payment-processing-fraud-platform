package main.queue;

import lombok.extern.slf4j.Slf4j;
import main.databaseModel.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;

/*
    Bounded, thread-safe queue that decouples HTTP submission from fraud analysis.
    Producers (PaymentService) enqueue without blocking - if full, callers get a 503.
    Consumers (TransactionWorkerPool) drain via take() on background threads.

    The queue stores transaction IDs rather than JPA entities. Workers reload each
    transaction inside their own transaction boundary before running fraud analysis.
*/
@Component
@Slf4j
public class TransactionQueue {

    private final LinkedBlockingQueue<String> queue;

    public TransactionQueue(@Value("${queue.capacity:10000}") int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    // Non-blocking offer: returns immediately so the HTTP thread is never parked here.
    public void enqueue(Transaction transaction) {
        boolean accepted = queue.offer(transaction.getId());
        if (!accepted) {
            log.warn("Transaction queue full - rejecting txn {}", transaction.getId());
            throw new TransactionQueueFullException();
        }
    }

    // Called by workers to take the next transaction (blocks when empty).
    public String take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public static class TransactionQueueFullException extends RuntimeException {
        public TransactionQueueFullException() {
            super("Transaction queue is full! Try again shortly.");
        }
    }
}
