package main.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import main.databaseModel.FraudAlert;
import main.databaseModel.Transaction;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/*
    Pushes one aggregated message per transaction to two STOMP destinations: the shared
    analyst feed and a per-sender feed for targeted dashboards. Called only for HIGH/CRITICAL
    results (TransactionWorkerPool gates on risk level) — LOW/MEDIUM alerts never reach here.
*/
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudAlertBroadcaster {

    private static final String ALL_ALERTS_TOPIC = "/topic/fraud-alerts";
    private static final String USER_ALERTS_TOPIC_PREFIX = "/topic/fraud-alerts/";

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(List<FraudAlert> alerts, Transaction transaction) {
        if (alerts.isEmpty()) return;

        FraudAlertBroadcastMessage message = FraudAlertBroadcastMessage.from(alerts, transaction);

        messagingTemplate.convertAndSend(ALL_ALERTS_TOPIC, message);
        messagingTemplate.convertAndSend(USER_ALERTS_TOPIC_PREFIX + transaction.getSender().getId(), message);

        log.info("Broadcast fraud alert: txn={}, riskLevel={}, ruleCount={}",
            transaction.getId(), transaction.getRiskLevel(), alerts.size());
    }
}
