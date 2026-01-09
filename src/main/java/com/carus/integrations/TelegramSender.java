package com.carus.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramSender {

  private final TelegramProperties props;
  private final RestTemplate telegramRestTemplate;

  /**
   * Просто отправляет любой текст. Без parse_mode.
   */
  public void sendText(String text) {
    sendText(text, true, false);
  }

  public void sendText(String text, boolean disableWebPreview, boolean silent) {
    if (text == null || text.isBlank()) return;

    String url = UriComponentsBuilder
        .fromHttpUrl(props.getApiBaseUrl())          // обычно https://api.telegram.org
        .pathSegment("bot" + props.getBotToken(), "sendMessage")
        .toUriString();

    // ВАЖНО: parse_mode вообще не шлём
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chat_id", props.getChatId());
    payload.put("text", text);
    payload.put("disable_web_page_preview", disableWebPreview);
    payload.put("disable_notification", silent);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

    int maxAttempts = 4;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ResponseEntity<String> resp =
            telegramRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        if (resp.getStatusCode().is2xxSuccessful()) {
          return;
        }

        log.warn("Telegram send failed (attempt {}): status={}, body={}",
            attempt, resp.getStatusCode(), safeTrim(resp.getBody()));
        sleepBackoff(attempt);

      } catch (HttpStatusCodeException e) {
        log.warn("Telegram HTTP error (attempt {}): status={}, body={}",
            attempt, e.getStatusCode(), safeTrim(e.getResponseBodyAsString()));
        sleepBackoff(attempt);

      } catch (Exception e) {
        log.warn("Telegram send exception (attempt {}): {}", attempt, e.getMessage(), e);
        sleepBackoff(attempt);
      }
    }

    log.error("Telegram send окончательно не удалось после {} попыток", maxAttempts);
  }

  private void sleepBackoff(int attempt) {
    long ms = (long) (1000L * Math.pow(2, attempt - 1)); // 1s,2s,4s,8s
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
  }

  private String safeTrim(String s) {
    if (s == null) return null;
    s = s.trim();
    return s.length() > 500 ? s.substring(0, 500) + "..." : s;
  }
}
