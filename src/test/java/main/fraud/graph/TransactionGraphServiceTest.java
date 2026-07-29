package main.fraud.graph;

import main.application.states.FraudRuleType;
import main.application.states.TransactionStatus;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import main.databaseModel.User;
import main.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransactionGraphService graph-analysis methods and GraphFraudRule.
 *
 * TransactionGraphService is constructed directly and init() / rebuildGraph() are
 * called explicitly because Spring @PostConstruct / @Scheduled do not run in unit tests.
 */
@ExtendWith(MockitoExtension.class)
class TransactionGraphServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    TransactionGraphService graphService;

    private User user(String id) {
        User u = User.builder()
            .name("User " + id)
            .email(id + "@example.com")
            .phoneNumber("+14155550001")
            .balance(new BigDecimal("1000.00"))
            .homeCountry("US")
            .build();

        u.setId(id);
        return u;
    }

    private Transaction merchantTxn(User sender, String merchant, double amount) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender)
            .merchant(merchant)
            .amount(BigDecimal.valueOf(amount))
            .status(TransactionStatus.APPROVED)
            .location("New York")
            .country("US")
            .build();
    }

    private Transaction p2pTxn(User sender, User receiver, double amount) {
        return Transaction.builder()
            .id("txn-" + System.nanoTime())
            .sender(sender)
            .receiver(receiver)
            .amount(BigDecimal.valueOf(amount))
            .status(TransactionStatus.APPROVED)
            .location("New York")
            .country("US")
            .build();
    }

    @BeforeEach
    void setup() {
        when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
            .thenReturn(List.of());

        graphService = new TransactionGraphService(transactionRepository);
        graphService.init();
    }

    @Nested
    class GraphAnalysisResultTests {

        @Test
        void clean_isNotSuspicious() {
            GraphAnalysisResult r = GraphAnalysisResult.clean();

            assertFalse(r.suspicious());
            assertEquals(0.0, r.riskScore(), 0.001);
            assertNull(r.signalType());
            assertNull(r.description());
        }

        @Test
        void suspicious_holdsAllFields() {
            GraphAnalysisResult r = GraphAnalysisResult.suspicious("CIRCULAR_FLOW", "desc", 35.0);

            assertTrue(r.suspicious());
            assertEquals("CIRCULAR_FLOW", r.signalType());
            assertEquals("desc", r.description());
            assertEquals(35.0, r.riskScore(), 0.001);
        }
    }

    @Nested
    class GraphBuildingTests {

        @Test
        void emptyGraph_afterInitWithNoTransactions() {
            assertEquals(0, graphService.getGraphVertexCount());
            assertEquals(0, graphService.getGraphEdgeCount());
        }

        @Test
        void rebuildGraph_loadsMerchantTransactions() {
            User alice = user("alice");

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of(merchantTxn(alice, "Amazon", 50.0)));

            graphService.rebuildGraph();

            assertEquals(2, graphService.getGraphVertexCount());
            assertEquals(1, graphService.getGraphEdgeCount());
        }

        @Test
        void rebuildGraph_loadsP2pTransactions() {
            User alice = user("alice");
            User bob = user("bob");

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of(p2pTxn(alice, bob, 100.0)));

            graphService.rebuildGraph();

            assertEquals(2, graphService.getGraphVertexCount());
            assertEquals(1, graphService.getGraphEdgeCount());
        }

        @Test
        void addTransaction_merchant_updatesLiveGraph() {
            User alice = user("alice");

            graphService.addTransaction(merchantTxn(alice, "Amazon", 50.0));

            assertEquals(2, graphService.getGraphVertexCount());
            assertEquals(1, graphService.getGraphEdgeCount());
        }

        @Test
        void addTransaction_p2p_updatesLiveGraph() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));

            assertEquals(2, graphService.getGraphVertexCount());
            assertEquals(1, graphService.getGraphEdgeCount());
        }

        @Test
        void addTransaction_multipleTransactions_accumulateEdges() {
            User alice = user("alice");

            graphService.addTransaction(merchantTxn(alice, "Amazon", 50.0));
            graphService.addTransaction(merchantTxn(alice, "Amazon", 30.0));

            assertEquals(2, graphService.getGraphEdgeCount());
        }

        @Test
        void rebuildGraph_replacesExistingGraph() {
            User alice = user("alice");

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of(merchantTxn(alice, "Amazon", 50.0)));

            graphService.rebuildGraph();
            assertEquals(2, graphService.getGraphVertexCount());

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of());

            graphService.rebuildGraph();
            assertEquals(0, graphService.getGraphVertexCount());
        }
    }

    @Nested
    class CircularFlowTests {

        @Test
        void userNotInGraph_returnsClean() {
            GraphAnalysisResult r = graphService.analyzeCircularFlow("unknown-user");

            assertFalse(r.suspicious());
        }

        @Test
        void noCircle_returnsClean() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));

            GraphAnalysisResult r = graphService.analyzeCircularFlow("alice");

            assertFalse(r.suspicious());
        }

        @Test
        void circularFlow_twoHop_detected() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            GraphAnalysisResult r = graphService.analyzeCircularFlow("alice");

            assertTrue(r.suspicious());
            assertEquals("CIRCULAR_FLOW", r.signalType());
            assertEquals(35.0, r.riskScore(), 0.001);
        }

        @Test
        void circularFlow_threeHop_detected() {
            User alice = user("alice");
            User bob = user("bob");
            User charlie = user("charlie");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));
            graphService.addTransaction(p2pTxn(bob, charlie, 90.0));
            graphService.addTransaction(p2pTxn(charlie, alice, 80.0));

            GraphAnalysisResult r = graphService.analyzeCircularFlow("alice");

            assertTrue(r.suspicious());
            assertEquals("CIRCULAR_FLOW", r.signalType());
        }

        @Test
        void merchantEdgesAreIgnoredForCircularFlowBfs() {
            User alice = user("alice");

            graphService.addTransaction(merchantTxn(alice, "Amazon", 50.0));

            GraphAnalysisResult r = graphService.analyzeCircularFlow("alice");

            assertFalse(r.suspicious());
        }
    }

    // Covers the gap analyzeCircularFlow cannot see: the transaction that itself closes a
    // ring hasn't been added to the graph yet when it's scored (addTransaction() only runs
    // after the verdict), so analyzeCircularFlow(sender) alone finds nothing.
    @Nested
    class WouldCompleteCycleTests {

        @Test
        void nullSenderOrReceiver_returnsClean() {
            assertFalse(graphService.wouldCompleteCycle(null, "bob").suspicious());
            assertFalse(graphService.wouldCompleteCycle("alice", null).suspicious());
        }

        @Test
        void senderEqualsReceiver_returnsClean() {
            assertFalse(graphService.wouldCompleteCycle("alice", "alice").suspicious());
        }

        @Test
        void receiverNotInGraph_returnsClean() {
            assertFalse(graphService.wouldCompleteCycle("alice", "unknown").suspicious());
        }

        @Test
        void noPathBackToSender_returnsClean() {
            User alice = user("alice");
            User bob = user("bob");
            User charlie = user("charlie");

            // bob -> charlie exists, but nothing routes back to alice
            graphService.addTransaction(p2pTxn(bob, charlie, 50.0));

            GraphAnalysisResult r = graphService.wouldCompleteCycle("alice", "bob");

            assertFalse(r.suspicious());
        }

        @Test
        void directReturnPath_detectsClosingEdge() {
            User alice = user("alice");
            User bob = user("bob");

            // bob -> alice already exists. alice -> bob (not yet added) would close a 2-cycle.
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            GraphAnalysisResult r = graphService.wouldCompleteCycle("alice", "bob");

            assertTrue(r.suspicious());
            assertEquals("CIRCULAR_FLOW", r.signalType());
            assertEquals(35.0, r.riskScore(), 0.001);
        }

        @Test
        void multiHopReturnPath_detectsClosingEdge() {
            User alice = user("alice");
            User bob = user("bob");
            User charlie = user("charlie");

            // bob -> charlie -> alice already exist. alice -> bob would close a 3-cycle.
            graphService.addTransaction(p2pTxn(bob, charlie, 90.0));
            graphService.addTransaction(p2pTxn(charlie, alice, 80.0));

            GraphAnalysisResult r = graphService.wouldCompleteCycle("alice", "bob");

            assertTrue(r.suspicious());
            assertEquals("CIRCULAR_FLOW", r.signalType());
        }

        @Test
        void doesNotMutateGraph() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(bob, alice, 90.0));
            int edgesBefore = graphService.getGraphEdgeCount();

            graphService.wouldCompleteCycle("alice", "bob");

            assertEquals(edgesBefore, graphService.getGraphEdgeCount());
        }

        @Test
        void merchantEdgesAreIgnored() {
            User alice = user("alice");
            User bob = user("bob");

            // bob only ever paid a merchant, never routed back to alice
            graphService.addTransaction(merchantTxn(bob, "Amazon", 50.0));

            GraphAnalysisResult r = graphService.wouldCompleteCycle("alice", "bob");

            assertFalse(r.suspicious());
        }
    }

    @Nested
    class NodeDegreeTests {

        @Test
        void userNotInGraph_returnsClean() {
            assertFalse(graphService.analyzeNodeDegree("unknown").suspicious());
        }

        @Test
        void lowDegree_returnsClean() {
            User alice = user("alice");

            graphService.addTransaction(merchantTxn(alice, "Amazon", 50.0));

            assertFalse(graphService.analyzeNodeDegree("alice").suspicious());
        }

        @Test
        void repeatedTransactionsToSameUser_doNotCreateHighUniqueDegree() {
            User alice = user("alice");
            User bob = user("bob");

            for (int i = 0; i < 100; i++) {
                graphService.addTransaction(p2pTxn(alice, bob, 10.0));
            }

            GraphAnalysisResult r = graphService.analyzeNodeDegree("alice");

            assertFalse(r.suspicious());
        }

        @Test
        void merchantEdges_doNotCountTowardUserCounterpartyDegree() {
            User hub = user("hub");

            for (int i = 0; i < 100; i++) {
                graphService.addTransaction(merchantTxn(hub, "Merchant_" + i, 10.0));
            }

            GraphAnalysisResult r = graphService.analyzeNodeDegree("hub");

            assertFalse(r.suspicious());
        }

        @Test
        void highTotalUniqueUserDegree_highDegreeNodeSignal() {
            User hub = user("hub");

            for (int i = 0; i < 51; i++) {
                User counterparty = user("receiver-" + i);
                graphService.addTransaction(p2pTxn(hub, counterparty, 10.0));
            }

            GraphAnalysisResult r = graphService.analyzeNodeDegree("hub");

            assertTrue(r.suspicious());
            assertEquals("HIGH_DEGREE_NODE", r.signalType());
            assertEquals(30.0, r.riskScore(), 0.001);
        }

        @Test
        void bidirectionalHub_detected() {
            User hub = user("hub");

            for (int i = 0; i < 21; i++) {
                User sender = user("sender-" + i);
                graphService.addTransaction(p2pTxn(sender, hub, 10.0));
            }

            for (int i = 0; i < 21; i++) {
                User receiver = user("receiver-" + i);
                graphService.addTransaction(p2pTxn(hub, receiver, 10.0));
            }

            GraphAnalysisResult r = graphService.analyzeNodeDegree("hub");

            assertTrue(r.suspicious());
            assertEquals("BIDIRECTIONAL_HUB", r.signalType());
            assertEquals(20.0, r.riskScore(), 0.001);
        }
    }

    // Covers the equivalent gap for node-degree: a transaction whose receiver is itself the
    // counterparty that crosses a threshold hasn't been added to the graph yet at scoring
    // time, so analyzeNodeDegree(sender) alone can't see it.
    @Nested
    class WouldExceedDegreeThresholdTests {

        @Test
        void nullSenderOrReceiver_returnsClean() {
            assertFalse(graphService.wouldExceedDegreeThreshold(null, "bob").suspicious());
            assertFalse(graphService.wouldExceedDegreeThreshold("alice", null).suspicious());
        }

        @Test
        void senderEqualsReceiver_returnsClean() {
            assertFalse(graphService.wouldExceedDegreeThreshold("alice", "alice").suspicious());
        }

        @Test
        void senderNotInGraph_returnsClean() {
            // A single new counterparty can never cross either threshold from zero.
            assertFalse(graphService.wouldExceedDegreeThreshold("alice", "bob").suspicious());
        }

        @Test
        void existingCounterparty_doesNotDoubleCount() {
            User alice = user("alice");

            // Exactly 50 unique counterparties - at, not over, the threshold.
            for (int i = 0; i < 50; i++) {
                graphService.addTransaction(p2pTxn(alice, user("peer-" + i), 10.0));
            }

            // Transacting again with an EXISTING counterparty adds no new node.
            GraphAnalysisResult r = graphService.wouldExceedDegreeThreshold("alice", "peer-0");

            assertFalse(r.suspicious());
        }

        @Test
        void newCounterpartyPushesOverThreshold_detectsBeforeEdgeExists() {
            User alice = user("alice");

            for (int i = 0; i < 50; i++) {
                graphService.addTransaction(p2pTxn(alice, user("peer-" + i), 10.0));
            }
            // Sanity check: not yet suspicious with the existing 50 alone.
            assertFalse(graphService.analyzeNodeDegree("alice").suspicious());

            // "peer-50" has never transacted with alice - this transaction would be new.
            GraphAnalysisResult r = graphService.wouldExceedDegreeThreshold("alice", "peer-50");

            assertTrue(r.suspicious());
            assertEquals("HIGH_DEGREE_NODE", r.signalType());
            assertEquals(30.0, r.riskScore(), 0.001);
        }

        @Test
        void newCounterpartyPushesIntoBidirectionalHub_detectsBeforeEdgeExists() {
            User hub = user("hub");

            for (int i = 0; i < 21; i++) {
                graphService.addTransaction(p2pTxn(user("sender-" + i), hub, 10.0));
            }
            for (int i = 0; i < 20; i++) {
                graphService.addTransaction(p2pTxn(hub, user("receiver-" + i), 10.0));
            }
            // Sanity check: out=20 is not yet > 20, so not suspicious with these alone.
            assertFalse(graphService.analyzeNodeDegree("hub").suspicious());

            // A 21st distinct outgoing counterparty would push out to 21.
            GraphAnalysisResult r = graphService.wouldExceedDegreeThreshold("hub", "receiver-20");

            assertTrue(r.suspicious());
            assertEquals("BIDIRECTIONAL_HUB", r.signalType());
            assertEquals(20.0, r.riskScore(), 0.001);
        }

        @Test
        void doesNotMutateGraph() {
            User alice = user("alice");
            graphService.addTransaction(p2pTxn(alice, user("bob"), 10.0));
            int edgesBefore = graphService.getGraphEdgeCount();

            graphService.wouldExceedDegreeThreshold("alice", "charlie");

            assertEquals(edgesBefore, graphService.getGraphEdgeCount());
        }
    }

    @Nested
    class ClusterTests {

        @Test
        void userNotInGraph_returnsClean() {
            assertFalse(graphService.analyzeCluster("unknown").suspicious());
        }

        @Test
        void smallCluster_returnsClean() {
            User alice = user("alice");
            User bob = user("bob");

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of(p2pTxn(alice, bob, 50.0)));

            graphService.rebuildGraph();

            assertFalse(graphService.analyzeCluster("alice").suspicious());
        }

        @Test
        void addTransaction_doesNotImmediatelyUpdateClusterSizes() {
            List<User> users = new ArrayList<>();

            for (int i = 0; i < 21; i++) {
                users.add(user("live-u" + i));
            }

            for (int i = 0; i < 20; i++) {
                graphService.addTransaction(p2pTxn(users.get(i), users.get(i + 1), 10.0));
            }

            assertFalse(graphService.analyzeCluster("live-u0").suspicious());
        }

        @Test
        void largeCluster_suspiciousAfterRebuild() {
            List<User> users = new ArrayList<>();

            for (int i = 0; i < 21; i++) {
                users.add(user("u" + i));
            }

            List<Transaction> txns = new ArrayList<>();

            for (int i = 0; i < 20; i++) {
                txns.add(p2pTxn(users.get(i), users.get(i + 1), 10.0));
            }

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(txns);

            graphService.rebuildGraph();

            GraphAnalysisResult r = graphService.analyzeCluster("u0");

            assertTrue(r.suspicious());
            assertEquals("SUSPICIOUS_CLUSTER", r.signalType());
            assertEquals(25.0, r.riskScore(), 0.001);
        }

        @Test
        void clusterSizesUpdatedOnRebuild() {
            List<User> users = new ArrayList<>();

            for (int i = 0; i < 21; i++) {
                users.add(user("u" + i));
            }

            List<Transaction> txns = new ArrayList<>();

            for (int i = 0; i < 20; i++) {
                txns.add(p2pTxn(users.get(i), users.get(i + 1), 10.0));
            }

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(txns);

            graphService.rebuildGraph();
            assertTrue(graphService.analyzeCluster("u0").suspicious());

            when(transactionRepository.findTransactionsSince(any(), any(Collection.class)))
                .thenReturn(List.of());

            graphService.rebuildGraph();
            assertFalse(graphService.analyzeCluster("u0").suspicious());
        }
    }

    @Nested
    class GraphFraudRuleTests {

        GraphFraudRule graphFraudRule;

        @BeforeEach
        void setup() {
            graphFraudRule = new GraphFraudRule(graphService);
        }

        private Transaction txnForUser(User user) {
            return Transaction.builder()
                .id("txn-" + System.nanoTime())
                .sender(user)
                .merchant("SomeMerchant")
                .amount(new BigDecimal("50.00"))
                .status(TransactionStatus.PENDING)
                .location("New York")
                .country("US")
                .build();
        }

        private Transaction pendingP2pTxn(User sender, User receiver) {
            return Transaction.builder()
                .id("txn-" + System.nanoTime())
                .sender(sender)
                .receiver(receiver)
                .amount(new BigDecimal("50.00"))
                .status(TransactionStatus.PENDING)
                .location("New York")
                .country("US")
                .build();
        }

        @Test
        void noSignals_noAlerts() {
            User alice = user("alice");

            graphService.addTransaction(merchantTxn(alice, "Amazon", 50.0));

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(alice));

            assertTrue(alerts.isEmpty());
        }

        @Test
        void circularFlowSignal_producesAlert() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(alice));

            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_CIRCULAR_FLOW));
        }

        @Test
        void highDegreeSignal_producesAlert() {
            User hub = user("hub");

            for (int i = 0; i < 51; i++) {
                User counterparty = user("peer-" + i);
                graphService.addTransaction(p2pTxn(hub, counterparty, 10.0));
            }

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(hub));

            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_HIGH_DEGREE_NODE));
        }

        @Test
        void multipleSignals_producesMultipleAlerts() {
            User hub = user("hub");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(hub, bob, 100.0));
            graphService.addTransaction(p2pTxn(bob, hub, 90.0));

            for (int i = 0; i < 51; i++) {
                User counterparty = user("peer-" + i);
                graphService.addTransaction(p2pTxn(hub, counterparty, 10.0));
            }

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(hub));

            assertTrue(alerts.size() >= 2);
            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_CIRCULAR_FLOW));
            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_HIGH_DEGREE_NODE));
        }

        @Test
        void nullSender_returnsNoAlerts() {
            Transaction txn = Transaction.builder()
                .id("txn-null-sender")
                .amount(new BigDecimal("50.00"))
                .status(TransactionStatus.PENDING)
                .build();

            List<FraudAlert> alerts = graphFraudRule.evaluate(txn);

            assertTrue(alerts.isEmpty());
        }

        // The gap this closes: the transaction being evaluated here is alice -> bob, and
        // that edge has NOT been added to the graph yet (evaluate() runs before
        // TransactionWorkerPool ever calls addTransaction()). Without wouldCompleteCycle,
        // analyzeCircularFlow("alice") alone would find nothing and this would score clean.
        @Test
        void p2pTransactionClosingCycle_producesAlertBeforeEdgeExists() {
            User alice = user("alice");
            User bob = user("bob");

            // Only the return leg exists so far; alice -> bob (this transaction) is what
            // would close the ring, and it is deliberately not added to the graph here.
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            List<FraudAlert> alerts = graphFraudRule.evaluate(pendingP2pTxn(alice, bob));

            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_CIRCULAR_FLOW));
        }

        @Test
        void merchantTransaction_neverChecksWouldCompleteCycle() {
            User alice = user("alice");
            User bob = user("bob");

            // Same ring-closing graph state as above, but this transaction is a merchant
            // payment (no receiver) — it cannot close a user cycle, so no alert.
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(alice));

            assertTrue(alerts.stream()
                .noneMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_CIRCULAR_FLOW));
        }

        // The gap this closes: alice -> peer-50 (this transaction) is what pushes alice past
        // 50 unique counterparties, and that edge has NOT been added to the graph yet.
        // Without wouldExceedDegreeThreshold, analyzeNodeDegree("alice") alone would still
        // only see 50 (not > 50) and this would score clean.
        @Test
        void p2pTransactionCrossingDegreeThreshold_producesAlertBeforeEdgeExists() {
            User alice = user("alice");

            for (int i = 0; i < 50; i++) {
                graphService.addTransaction(p2pTxn(alice, user("peer-" + i), 10.0));
            }

            List<FraudAlert> alerts = graphFraudRule.evaluate(pendingP2pTxn(alice, user("peer-50")));

            assertTrue(alerts.stream()
                .anyMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_HIGH_DEGREE_NODE));
        }

        @Test
        void merchantTransaction_neverChecksWouldExceedDegreeThreshold() {
            User alice = user("alice");

            // Same near-threshold graph state as above, but this transaction is a merchant
            // payment — merchant edges never count toward user counterparty degree, so the
            // plain analyzeNodeDegree(alice) applies and correctly stays clean at 50.
            for (int i = 0; i < 50; i++) {
                graphService.addTransaction(p2pTxn(alice, user("peer-" + i), 10.0));
            }

            List<FraudAlert> alerts = graphFraudRule.evaluate(txnForUser(alice));

            assertTrue(alerts.stream()
                .noneMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_HIGH_DEGREE_NODE));
        }

        @Test
        void p2pTransactionToExistingCounterparty_doesNotDoubleCount() {
            User alice = user("alice");
            User existingPeer = user("peer-0");

            for (int i = 0; i < 50; i++) {
                graphService.addTransaction(p2pTxn(alice, user("peer-" + i), 10.0));
            }

            // Transacting again with an already-known counterparty adds no new node.
            List<FraudAlert> alerts = graphFraudRule.evaluate(pendingP2pTxn(alice, existingPeer));

            assertTrue(alerts.stream()
                .noneMatch(a -> a.getRuleType() == FraudRuleType.GRAPH_HIGH_DEGREE_NODE));
        }

        // analyzeCircularFlow(alice) and wouldCompleteCycle(alice, bob) both notice the same
        // alice<->bob ring here - two mechanisms flagging one problem, not two rings. Without
        // collapsing by rule type this produced two GRAPH_CIRCULAR_FLOW alerts and double the
        // score (70.0 instead of 35.0) for a single ring.
        @Test
        void duplicateCircularFlowSignals_collapseToSingleAlert() {
            User alice = user("alice");
            User bob = user("bob");

            graphService.addTransaction(p2pTxn(alice, bob, 100.0));
            graphService.addTransaction(p2pTxn(bob, alice, 90.0));

            List<FraudAlert> alerts = graphFraudRule.evaluate(pendingP2pTxn(alice, bob));

            List<FraudAlert> circularFlowAlerts = alerts.stream()
                .filter(a -> a.getRuleType() == FraudRuleType.GRAPH_CIRCULAR_FLOW)
                .toList();

            assertEquals(1, circularFlowAlerts.size());
            assertEquals(35.0, circularFlowAlerts.get(0).getScoreContribution(), 0.001);
        }

        @Test
        void ruleMetadata() {
            assertEquals("GRAPH_ANALYSIS", graphFraudRule.getRuleName());
            assertEquals(50, graphFraudRule.getPriority());
        }
    }
}