package com.carus.integrations;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends bet signals to the remote bet-executor-service via POST /api/bet-signal.
 * Fire-and-forget: failures are logged but never propagate to the parser loop.
 */
@Service
public class BetExecutorClient {

    private final RestTemplate restTemplate;
    private final BetExecutorProperties props;

    public BetExecutorClient(RestTemplate restTemplate, BetExecutorProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /**
     * Posts a bet signal to the executor. Does nothing if executor is disabled.
     * Never throws — failures are printed and swallowed.
     */
    public void sendBetSignal(String event, String market,
                              BigDecimal odds, BigDecimal amount, String eventUrl) {
        if (!props.isEnabled()) {
            System.out.println("[executor] disabled, skipping: " + event);
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("market", market);
        body.put("odds", odds);
        body.put("amount", amount);
        body.put("eventUrl", eventUrl);

        try {
            String url = props.getUrl() + "/api/bet-signal";
            var response = restTemplate.postForEntity(url, body, Map.class);
            System.out.println("[executor] accepted signal: event='" + event
                    + "' status=" + response.getStatusCode());
        } catch (Exception e) {
            System.out.println("[executor] ERROR sending signal: event='" + event
                    + "' error=" + e.getMessage());
        }
    }
}
