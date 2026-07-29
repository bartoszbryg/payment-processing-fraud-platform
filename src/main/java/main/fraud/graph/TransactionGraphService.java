package main.fraud.graph;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import main.application.states.TransactionStatus;
import main.databaseModel.Transaction;
import main.repository.TransactionRepository;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import org.jgrapht.graph.SimpleGraph;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.StampedLock;

/**
 * In-memory directed weighted graph of recent transactions for pattern-based fraud detection.
 *
 * Node types:
 * - User IDs: plain UUID strings
 * - Merchant nodes: "MERCHANT:<name>"
 *
 * Edge types:
 * - user -> MERCHANT:<name> for merchant payments
 * - user -> user for P2P transfers
 *
 * Performance design:
 * - Full graph rebuild happens on a schedule.
 * - Expensive cluster computation is done during rebuild, not per transaction.
 * - analyzeCluster() is O(1): volatile map read + HashMap lookup.
 * - StampedLock protects the mutable JGraphT graph.
 * - Graph traversal methods use real read locks because JGraphT graphs are mutable
 *   and are not safe to walk during concurrent writes.
 * - rebuildGraph builds a fresh graph outside the write lock, then swaps references quickly.
 * - addTransaction holds the write lock while mutating the live graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionGraphService {

    private static final String MERCHANT_PREFIX = "MERCHANT:";
    private static final int MAX_CIRCULAR_SEARCH_NODES = 50;

    private static final Set<TransactionStatus> EXCLUDED = Set.of(
        TransactionStatus.FRAUD_BLOCKED,
        TransactionStatus.DECLINED
    );

    private final TransactionRepository transactionRepository;

    private final StampedLock lock = new StampedLock();

    private DirectedWeightedPseudograph<String, DefaultWeightedEdge> graph =
        new DirectedWeightedPseudograph<>(DefaultWeightedEdge.class);

    /**
     * Precomputed once per rebuild: userId -> connected component size.
     *
     * Volatile is enough because the map reference is swapped atomically and the map itself
     * is immutable after construction.
     */
    private volatile Map<String, Integer> clusterSizes = Map.of();

    @PostConstruct
    public void init() {
        rebuildGraph();

        long stamp = lock.readLock();
        try {
            log.info("Transaction graph initialized: {} vertices, {} edges",
                graph.vertexSet().size(),
                graph.edgeSet().size());
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Full rebuild from recent transactions.
     *
     * Expensive work happens before the write lock:
     * - database fetch
     * - graph construction
     * - connected component computation
     *
     * The write lock is held only for the final pointer swap.
     */
    @Scheduled(fixedDelayString = "${fraud.graph.rebuild-interval-ms:300000}")
    public void rebuildGraph() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Transaction> recent = transactionRepository.findTransactionsSince(since, EXCLUDED);

        DirectedWeightedPseudograph<String, DefaultWeightedEdge> fresh =
            new DirectedWeightedPseudograph<>(DefaultWeightedEdge.class);

        loadInto(fresh, recent);

        Map<String, Integer> freshClusterSizes = computeClusterSizes(fresh);

        long stamp = lock.writeLock();
        try {
            this.graph = fresh;
            this.clusterSizes = freshClusterSizes;
        } finally {
            lock.unlockWrite(stamp);
        }

        log.debug("Graph rebuilt: {} vertices, {} edges, {} user components from {} transactions",
            fresh.vertexSet().size(),
            fresh.edgeSet().size(),
            freshClusterSizes.size(),
            recent.size());
    }

    /**
     * Incrementally adds approved transactions between scheduled rebuilds.
     *
     * clusterSizes is intentionally not updated here for performance.
     * The next scheduled rebuild recalculates cluster sizes from scratch.
     */
    public void addTransaction(Transaction transaction) {
        long stamp = lock.writeLock();
        try {
            addTransactionToGraph(graph, transaction);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * BFS to detect whether funds flow back to the originating user.
     *
     * Uses ArrayDeque instead of LinkedList for faster queue operations.
     * Nodes are marked when enqueued to avoid repeated queue growth in dense subgraphs.
     */
    public GraphAnalysisResult analyzeCircularFlow(String userId) {
        long stamp = lock.readLock();
        try {
            return circularFlowUnderLock(userId);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private GraphAnalysisResult circularFlowUnderLock(String userId) {
        if (!graph.containsVertex(userId)) {
            return GraphAnalysisResult.clean();
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        visited.add(userId);

        for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(userId)) {
            String target = graph.getEdgeTarget(edge);

            if (!isUserNode(target)) {
                continue;
            }

            if (target.equals(userId)) {
                return GraphAnalysisResult.suspicious(
                    "CIRCULAR_FLOW",
                    "Circular money flow: funds return directly to originating user",
                    35.0
                );
            }

            if (visited.add(target)) {
                queue.add(target);
            }
        }

        // Bounded on nodes expanded (dequeued), not nodes seen: a hub with a large direct
        // out-degree already adds more than MAX_CIRCULAR_SEARCH_NODES entries to `visited`
        // before this loop even starts, so capping on visited.size() would stop the BFS
        // before it expands a single one of them — silently skipping exactly the neighbor
        // that closes the cycle. Capping on how many nodes get dequeued still explores every
        // direct neighbor (the cheap, useful part) and only bounds the expensive multi-hop
        // expansion beyond that, so a dense connected component can't make every call for
        // any node in it cost proportional to the whole component's size.
        int expanded = 0;
        while (!queue.isEmpty() && expanded < MAX_CIRCULAR_SEARCH_NODES) {
            String current = queue.poll();
            expanded++;

            for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(current)) {
                String next = graph.getEdgeTarget(edge);

                if (!isUserNode(next)) {
                    continue;
                }

                if (next.equals(userId)) {
                    return GraphAnalysisResult.suspicious(
                        "CIRCULAR_FLOW",
                        "Circular money flow: funds return to originating user",
                        35.0
                    );
                }

                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        return GraphAnalysisResult.clean();
    }

    /**
     * Checks whether a not-yet-existing sender -> receiver edge would complete a cycle back
     * to the sender, WITHOUT mutating the shared graph.
     *
     * Why this exists: addTransaction() only runs after TransactionWorkerPool has already
     * decided APPROVED/FLAGGED/BLOCKED and (for APPROVED) deducted the balance. At scoring
     * time the sender -> receiver edge for the transaction being evaluated does not exist in
     * the graph yet, so analyzeCircularFlow(senderId) — which only walks existing outgoing
     * edges — cannot see a cycle that this specific transaction would close. The transaction
     * that completes a ring would otherwise sail through clean and only get caught, after the
     * fact, by whichever transaction happens to touch that ring next.
     *
     * A future edge sender -> receiver closes a cycle iff receiver can already reach sender
     * via existing user -> user edges — so this runs the same bounded BFS as
     * analyzeCircularFlow, just started from receiver instead of sender, under a read lock
     * only. No edge is added speculatively, so there is nothing to undo if the verdict turns
     * out to be BLOCKED.
     */
    public GraphAnalysisResult wouldCompleteCycle(String senderId, String receiverId) {
        long stamp = lock.readLock();
        try {
            return wouldCompleteCycleUnderLock(senderId, receiverId);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private GraphAnalysisResult wouldCompleteCycleUnderLock(String senderId, String receiverId) {
        if (senderId == null || receiverId == null || senderId.equals(receiverId)) {
            return GraphAnalysisResult.clean();
        }

        if (!graph.containsVertex(receiverId)) {
            return GraphAnalysisResult.clean();
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        visited.add(receiverId);
        queue.add(receiverId);

        // Same expanded-count bound as circularFlowUnderLock — see the comment there for why
        // capping on visited.size() instead would let a large direct out-degree stop the
        // search before it explores a single neighbor.
        int expanded = 0;
        while (!queue.isEmpty() && expanded < MAX_CIRCULAR_SEARCH_NODES) {
            String current = queue.poll();
            expanded++;

            for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(current)) {
                String next = graph.getEdgeTarget(edge);

                if (!isUserNode(next)) {
                    continue;
                }

                if (next.equals(senderId)) {
                    return GraphAnalysisResult.suspicious(
                        "CIRCULAR_FLOW",
                        "This transaction would complete a circular money flow back to the sender",
                        35.0
                    );
                }

                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        return GraphAnalysisResult.clean();
    }

    /**
     * Detects hub/mule behavior using unique user counterparties.
     *
     * This is usually better than graph.inDegreeOf/outDegreeOf because a pseudograph counts
     * repeated transactions between the same two users as multiple edges.
     */
    public GraphAnalysisResult analyzeNodeDegree(String userId) {
        long stamp = lock.readLock();
        try {
            return nodeDegreeUnderLock(userId);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private GraphAnalysisResult nodeDegreeUnderLock(String userId) {
        if (!graph.containsVertex(userId)) {
            return GraphAnalysisResult.clean();
        }

        Set<String> outgoingUsers = new HashSet<>();
        Set<String> incomingUsers = new HashSet<>();

        for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(userId)) {
            String target = graph.getEdgeTarget(edge);

            if (isUserNode(target) && !target.equals(userId)) {
                outgoingUsers.add(target);
            }
        }

        for (DefaultWeightedEdge edge : graph.incomingEdgesOf(userId)) {
            String source = graph.getEdgeSource(edge);

            if (isUserNode(source) && !source.equals(userId)) {
                incomingUsers.add(source);
            }
        }

        int out = outgoingUsers.size();
        int in = incomingUsers.size();

        Set<String> allCounterparties = new HashSet<>(outgoingUsers);
        allCounterparties.addAll(incomingUsers);

        int totalUnique = allCounterparties.size();

        if (totalUnique > 50) {
            return GraphAnalysisResult.suspicious(
                "HIGH_DEGREE_NODE",
                String.format("High-degree node (%d unique counterparties) — potential money mule", totalUnique),
                30.0
            );
        }

        if (in > 20 && out > 20) {
            return GraphAnalysisResult.suspicious(
                "BIDIRECTIONAL_HUB",
                String.format("Hub behaviour: incoming counterparties=%d, outgoing counterparties=%d", in, out),
                20.0
            );
        }

        return GraphAnalysisResult.clean();
    }

    /**
     * Checks whether this not-yet-existing sender -> receiver edge would itself push the
     * sender's unique-counterparty count past HIGH_DEGREE_NODE or BIDIRECTIONAL_HUB,
     * WITHOUT mutating the shared graph.
     *
     * Same gap as wouldCompleteCycle: addTransaction() only runs after the verdict is
     * decided, so analyzeNodeDegree(sender) alone counts only pre-existing counterparties —
     * if receiver is new to sender, it cannot see that this transaction is itself the one
     * that crosses the threshold. Used in place of analyzeNodeDegree for P2P transactions;
     * when receiver is already a known counterparty, adding it to the simulated set is a
     * no-op and this returns the same verdict analyzeNodeDegree would.
     */
    public GraphAnalysisResult wouldExceedDegreeThreshold(String senderId, String receiverId) {
        long stamp = lock.readLock();
        try {
            return wouldExceedDegreeThresholdUnderLock(senderId, receiverId);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private GraphAnalysisResult wouldExceedDegreeThresholdUnderLock(String senderId, String receiverId) {
        if (senderId == null || receiverId == null || senderId.equals(receiverId)) {
            return GraphAnalysisResult.clean();
        }

        // No prior edges at all — a single new counterparty can't cross either threshold.
        if (!graph.containsVertex(senderId)) {
            return GraphAnalysisResult.clean();
        }

        Set<String> outgoingUsers = new HashSet<>();
        Set<String> incomingUsers = new HashSet<>();

        for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(senderId)) {
            String target = graph.getEdgeTarget(edge);

            if (isUserNode(target) && !target.equals(senderId)) {
                outgoingUsers.add(target);
            }
        }

        for (DefaultWeightedEdge edge : graph.incomingEdgesOf(senderId)) {
            String source = graph.getEdgeSource(edge);

            if (isUserNode(source) && !source.equals(senderId)) {
                incomingUsers.add(source);
            }
        }

        // Simulate the not-yet-existing edge in the local copy only — a no-op if receiver
        // is already a known counterparty.
        outgoingUsers.add(receiverId);

        int out = outgoingUsers.size();
        int in = incomingUsers.size();

        Set<String> allCounterparties = new HashSet<>(outgoingUsers);
        allCounterparties.addAll(incomingUsers);

        int totalUnique = allCounterparties.size();

        if (totalUnique > 50) {
            return GraphAnalysisResult.suspicious(
                "HIGH_DEGREE_NODE",
                String.format(
                    "This transaction would push %s to %d unique counterparties — potential money mule",
                    senderId, totalUnique),
                30.0
            );
        }

        if (in > 20 && out > 20) {
            return GraphAnalysisResult.suspicious(
                "BIDIRECTIONAL_HUB",
                String.format(
                    "This transaction would create hub behaviour: incoming=%d, outgoing=%d", in, out),
                20.0
            );
        }

        return GraphAnalysisResult.clean();
    }

    /**
     * O(1) cluster size lookup.
     *
     * No graph traversal. No lock. The map is immutable and safely published through volatile.
     */
    public GraphAnalysisResult analyzeCluster(String userId) {
        Integer size = clusterSizes.get(userId);

        if (size == null || size <= 20) {
            return GraphAnalysisResult.clean();
        }

        return GraphAnalysisResult.suspicious(
            "SUSPICIOUS_CLUSTER",
            String.format("Part of large transaction cluster (%d accounts)", size),
            25.0
        );
    }

    public int getGraphVertexCount() {
        long stamp = lock.readLock();
        try {
            return graph.vertexSet().size();
        } finally {
            lock.unlockRead(stamp);
        }
    }

    public int getGraphEdgeCount() {
        long stamp = lock.readLock();
        try {
            return graph.edgeSet().size();
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Computes connected component sizes over user-user relationships only.
     *
     * Merchant nodes are skipped because they would connect unrelated customers through
     * popular merchants and create misleading giant clusters.
     */
    private Map<String, Integer> computeClusterSizes(
            DirectedWeightedPseudograph<String, DefaultWeightedEdge> sourceGraph) {

        SimpleGraph<String, DefaultEdge> undirected = new SimpleGraph<>(DefaultEdge.class);

        for (String vertex : sourceGraph.vertexSet()) {
            if (isUserNode(vertex)) {
                undirected.addVertex(vertex);
            }
        }

        for (DefaultWeightedEdge edge : sourceGraph.edgeSet()) {
            String source = sourceGraph.getEdgeSource(edge);
            String target = sourceGraph.getEdgeTarget(edge);

            if (isUserNode(source) && isUserNode(target) && !source.equals(target)) {
                undirected.addEdge(source, target);
            }
        }

        Map<String, Integer> result = new HashMap<>();

        for (Set<String> component : new ConnectivityInspector<>(undirected).connectedSets()) {
            int size = component.size();

            for (String userId : component) {
                result.put(userId, size);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private void loadInto(
            DirectedWeightedPseudograph<String, DefaultWeightedEdge> targetGraph,
            List<Transaction> transactions) {

        for (Transaction transaction : transactions) {
            addTransactionToGraph(targetGraph, transaction);
        }
    }

    private void addTransactionToGraph(
            DirectedWeightedPseudograph<String, DefaultWeightedEdge> targetGraph,
            Transaction transaction) {

        if (transaction.getSender() == null || transaction.getSender().getId() == null) {
            return;
        }

        String senderId = transaction.getSender().getId();

        if (transaction.getMerchant() != null && !transaction.getMerchant().isBlank()) {
            String merchantNode = merchantNode(transaction.getMerchant());

            targetGraph.addVertex(senderId);
            targetGraph.addVertex(merchantNode);

            DefaultWeightedEdge edge = targetGraph.addEdge(senderId, merchantNode);

            if (edge != null && transaction.getAmount() != null) {
                targetGraph.setEdgeWeight(edge, transaction.getAmount().doubleValue());
            }

            return;
        }

        if (transaction.getReceiver() != null && transaction.getReceiver().getId() != null) {
            String receiverId = transaction.getReceiver().getId();

            targetGraph.addVertex(senderId);
            targetGraph.addVertex(receiverId);

            DefaultWeightedEdge edge = targetGraph.addEdge(senderId, receiverId);

            if (edge != null && transaction.getAmount() != null) {
                targetGraph.setEdgeWeight(edge, transaction.getAmount().doubleValue());
            }
        }
    }

    private static boolean isUserNode(String node) {
        return node != null && !node.startsWith(MERCHANT_PREFIX);
    }

    private static String merchantNode(String merchantName) {
        return MERCHANT_PREFIX + merchantName.trim();
    }
}