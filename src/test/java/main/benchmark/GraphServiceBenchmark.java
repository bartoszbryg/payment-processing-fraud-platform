package main.benchmark;

import main.application.states.TransactionStatus;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.fraud.graph.TransactionGraphService;
import main.repository.TransactionRepository;
import org.mockito.Mockito;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Benchmarks TransactionGraphService hot paths.
 *
 * Measured cases:
 * - addTransaction_merchant: pure write-lock + graph insert cost for merchant edge
 * - addTransaction_p2p: pure write-lock + graph insert cost for a P2P edge
 * - analyzeCircularFlow_hit: BFS finds a 2-hop ring
 * - analyzeCircularFlow_miss: BFS scans a chain without a ring
 * - analyzeNodeDegree_hub: unique-counterparty hub detection (read-locked)
 * - analyzeCluster_hit: O(1) volatile map read, present user
 * - analyzeCluster_miss: O(1) volatile map read, missing user
 * - rebuildGraph_small: full rebuild from 100 transactions
 * - rebuildGraph_large: full rebuild from 1,000 transactions
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class GraphServiceBenchmark {

    /**
     * Per-invocation state for addTransaction benchmarks.
     *
     * @Setup(Level.Invocation) runs before every single benchmark call.
     * Its cost is excluded from the measurement — only the addTransaction() call itself
     * is timed. This isolates the write-lock + graph-insert cost from Mockito setup.
     *
     * Trade-off: Level.Invocation adds a small coordination overhead compared to
     * Level.Trial. For operations faster than ~1 µs this can distort results; for
     * addTransaction (which holds a write lock and modifies the graph) the overhead
     * is negligible relative to the operation being measured.
     */
    @State(Scope.Thread)
    public static class FreshServiceState {

        TransactionGraphService service;

        @Setup(Level.Invocation)
        public void setUp() {
            TransactionRepository repo = Mockito.mock(TransactionRepository.class);
            when(repo.findTransactionsSince(any(), any(Collection.class))).thenReturn(List.of());
            service = new TransactionGraphService(repo);
            service.init();
        }
    }

    // services under test

    private TransactionGraphService serviceWithRing;
    private TransactionGraphService serviceLinear;
    private TransactionGraphService serviceHub;
    private TransactionGraphService serviceCluster;
    private TransactionGraphService serviceForSmallRebuild;
    private TransactionGraphService serviceForLargeRebuild;

    // pre-built transactions

    private Transaction merchantTxn;
    private Transaction p2pTxn;

    @Setup(Level.Trial)
    public void setup() {
        // Ring: alice → bob → alice
        User alice = user("alice");
        User bob   = user("bob");
        serviceWithRing = buildService(List.of());
        serviceWithRing.addTransaction(p2p(alice, bob, 100.0));
        serviceWithRing.addTransaction(p2p(bob, alice,  90.0));

        // Linear chain: 20 users, no cycle
        serviceLinear = buildService(List.of());
        List<User> chain = new ArrayList<>();
        for (int i = 0; i < 20; i++) chain.add(user("chain-" + i));
        for (int i = 0; i < 19; i++) serviceLinear.addTransaction(p2p(chain.get(i), chain.get(i + 1), 10.0));

        // Hub: 51 distinct P2P counterparties → HIGH_DEGREE_NODE
        User hub = user("hub");
        serviceHub = buildService(List.of());
        for (int i = 0; i < 51; i++) serviceHub.addTransaction(p2p(hub, user("hub-peer-" + i), 10.0));

        // Large cluster: 25-user chain rebuilt so clusterSizes is precomputed
        List<User> clusterUsers = new ArrayList<>();
        for (int i = 0; i < 25; i++) clusterUsers.add(user("cu" + i));
        List<Transaction> clusterTxns = new ArrayList<>();
        for (int i = 0; i < 24; i++) clusterTxns.add(p2p(clusterUsers.get(i), clusterUsers.get(i + 1), 10.0));
        serviceCluster = buildService(clusterTxns);

        // Transactions used as input for addTransaction benchmarks
        User sender   = user("sender");
        User receiver = user("receiver");
        merchantTxn = merchant(sender, "BenchMerchant", 50.0);
        p2pTxn      = p2p(sender, receiver, 50.0);

        // Rebuild benchmarks: repo wired once here so Mockito cost is outside the loop
        TransactionRepository repoSmall = Mockito.mock(TransactionRepository.class);
        when(repoSmall.findTransactionsSince(any(), any(Collection.class))).thenReturn(txnList(100));
        serviceForSmallRebuild = new TransactionGraphService(repoSmall);
        serviceForSmallRebuild.init();

        TransactionRepository repoLarge = Mockito.mock(TransactionRepository.class);
        when(repoLarge.findTransactionsSince(any(), any(Collection.class))).thenReturn(txnList(1_000));
        serviceForLargeRebuild = new TransactionGraphService(repoLarge);
        serviceForLargeRebuild.init();
    }

    // add transaction (isolated via per-invocation fresh service)

    @Benchmark
    public void addTransaction_merchant(FreshServiceState state, Blackhole bh) {
        state.service.addTransaction(merchantTxn);
        bh.consume(state.service.getGraphEdgeCount());
    }

    @Benchmark
    public void addTransaction_p2p(FreshServiceState state, Blackhole bh) {
        state.service.addTransaction(p2pTxn);
        bh.consume(state.service.getGraphEdgeCount());
    }

    // analyze circular flow

    @Benchmark
    public void analyzeCircularFlow_hit(Blackhole bh) {
        bh.consume(serviceWithRing.analyzeCircularFlow("alice"));
    }

    @Benchmark
    public void analyzeCircularFlow_miss(Blackhole bh) {
        bh.consume(serviceLinear.analyzeCircularFlow("chain-0"));
    }

    // analyze node degree

    @Benchmark
    public void analyzeNodeDegree_hub(Blackhole bh) {
        bh.consume(serviceHub.analyzeNodeDegree("hub"));
    }

    // analyze cluster (O(1) volatile read)

    @Benchmark
    public void analyzeCluster_hit(Blackhole bh) {
        bh.consume(serviceCluster.analyzeCluster("cu0"));
    }

    @Benchmark
    public void analyzeCluster_miss(Blackhole bh) {
        bh.consume(serviceCluster.analyzeCluster("nobody"));
    }

    // full graph rebuild

    @Benchmark
    public void rebuildGraph_small(Blackhole bh) {
        serviceForSmallRebuild.rebuildGraph();
        bh.consume(serviceForSmallRebuild.getGraphVertexCount());
    }

    @Benchmark
    public void rebuildGraph_large(Blackhole bh) {
        serviceForLargeRebuild.rebuildGraph();
        bh.consume(serviceForLargeRebuild.getGraphVertexCount());
    }

    // concurrent benchmarks

    /**
     * Shared state for concurrent benchmarks — one service instance used by all threads.
     *
     * Models the real deployment scenario: many transaction-processing threads calling
     * analyzeCircularFlow/analyzeNodeDegree simultaneously, with occasional background
     * writes from addTransaction. This is where StampedLock's optimistic reads pay off
     * over ReentrantReadWriteLock: readers that don't race a write acquire no lock at all.
     */
    @State(Scope.Benchmark)
    public static class SharedServiceState {

        TransactionGraphService serviceWithRing;
        TransactionGraphService serviceHub;
        Transaction             incomingTxn;

        @Setup(Level.Trial)
        public void setUp() {
            TransactionRepository repo = Mockito.mock(TransactionRepository.class);
            when(repo.findTransactionsSince(any(), any(Collection.class))).thenReturn(List.of());

            User alice = makeUser("alice-shared");
            User bob = makeUser("bob-shared");

            serviceWithRing = buildService(repo);
            serviceWithRing.addTransaction(makeP2p(alice, bob, 100.0));
            serviceWithRing.addTransaction(makeP2p(bob, alice, 90.0));

            User hub = makeUser("hub-shared");
            serviceHub = buildService(repo);
            for (int i = 0; i < 51; i++) {
                serviceHub.addTransaction(makeP2p(hub, makeUser("peer-" + i), 10.0));
            }

            User sender = makeUser("concurrent-sender");
            User receiver = makeUser("concurrent-receiver");
            incomingTxn = makeP2p(sender, receiver, 50.0);
        }

        private static TransactionGraphService buildService(TransactionRepository repo) {
            TransactionGraphService svc = new TransactionGraphService(repo);
            svc.init();
            return svc;
        }
    }

    /**
     * 8 threads calling analyzeCircularFlow concurrently — no writes.
     * Optimistic reads succeed on every call: lock.validate() passes, no lock acquired.
     */
    @Benchmark
    @Threads(8)
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void concurrent_reads_circularFlow(SharedServiceState state, Blackhole bh) {
        bh.consume(state.serviceWithRing.analyzeCircularFlow("alice-shared"));
    }

    /**
     * 7 reader threads + 1 writer thread.
     * Models steady-state: most transactions are analyzed (reads), occasionally one
     * is added to the live graph (write). Optimistic reads fail when the writer holds
     * the write lock and fall back to a full read lock for that call only.
     */
    @Benchmark
    @Threads(8)
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void concurrent_readWrite_circularFlow(SharedServiceState state, Blackhole bh) {
        // JMH distributes threads round-robin across benchmark methods within a group,
        // but since this is a single method all 8 threads call it. We simulate the
        // read-heavy / occasional-write ratio by having each thread decide based on its
        // thread ID: 1 in 8 does a write, the rest do reads.
        if (Thread.currentThread().getId() % 8 == 0) {
            state.serviceWithRing.addTransaction(state.incomingTxn);
        } else {
            bh.consume(state.serviceWithRing.analyzeCircularFlow("alice-shared"));
        }
    }

    /**
     * 8 threads calling analyzeNodeDegree concurrently on a hub node.
     */
    @Benchmark
    @Threads(8)
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void concurrent_reads_nodeDegree(SharedServiceState state, Blackhole bh) {
        bh.consume(state.serviceHub.analyzeNodeDegree("hub-shared"));
    }

    // helpers

    private TransactionGraphService buildService(List<Transaction> txns) {
        TransactionRepository repo = Mockito.mock(TransactionRepository.class);
        when(repo.findTransactionsSince(any(), any(Collection.class))).thenReturn(txns);
        TransactionGraphService svc = new TransactionGraphService(repo);
        svc.init();
        return svc;
    }

    private List<Transaction> txnList(int count) {
        List<Transaction> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            User u = user("u" + i);
            list.add(merchant(u, "Merchant_" + (i % 50), 10.0));
        }
        return list;
    }

    private User user(String id) { return makeUser(id); }
    private Transaction merchant(User s, String m, double a) { return makeMerchant(s, m, a); }
    private Transaction p2p(User s, User r, double a) { return makeP2p(s, r, a); }

    static User makeUser(String id) {
        User u = User.builder()
            .name("User " + id).email(id + "@bench.com")
            .phoneNumber("+14155550001")
            .balance(BigDecimal.valueOf(1000.0))
            .homeCountry("US").build();
        u.setId(id);
        return u;
    }

    static Transaction makeMerchant(User sender, String merchantName, double amount) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender).merchant(merchantName)
            .amount(BigDecimal.valueOf(amount))
            .status(TransactionStatus.APPROVED)
            .location("New York").country("US").build();
    }

    static Transaction makeP2p(User sender, User receiver, double amount) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender).receiver(receiver)
            .amount(BigDecimal.valueOf(amount))
            .status(TransactionStatus.APPROVED)
            .location("New York").country("US").build();
    }
}