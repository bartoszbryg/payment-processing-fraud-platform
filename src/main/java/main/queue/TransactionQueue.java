package main.queue;

/*
    Placeholder for Phase 7 - full blocking-queue + thread-pool implementation lives here
    The inner exception is defined now so GlobalExceptionHandler can reference it
*/

public class TransactionQueue {

    public static class TransactionQueueFullException extends RuntimeException {
        public TransactionQueueFullException() {
            super("Transaction queue is full! Try again shortly.");
        }
    }    
}
