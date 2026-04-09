package com.carus.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
   * Просто отправляет любой текст в дефолтный chat_id из props. Без parse_mode.
   */
  public void sendText(String text) {
    sendText(null, text, true, false);
  }

  public void sendText(String text, boolean disableWebPreview, boolean silent) {
    sendText(null, text, disableWebPreview, silent);
  }

  /**
   * Отправка в конкретный чат (если chatId == null/blank -> берём props.getChatId()).
   */
  public void sendText(String chatId, String text) {
    sendText(chatId, text, true, false);
  }

  public void sendText(String chatId, String text, boolean disableWebPreview, boolean silent) {
    if (text == null || text.isBlank()) return;

    String resolvedChatId = resolveChatId(chatId);
    if (resolvedChatId == null || resolvedChatId.isBlank()) {
      log.warn("Telegram chat_id пустой: передан={}, props.chatId={}", chatId, props.getChatId());
      return;
    }

    String url = UriComponentsBuilder
        .fromHttpUrl(props.getApiBaseUrl())          // обычно https://api.telegram.org
        .pathSegment("bot" + props.getBotToken(), "sendMessage")
        .toUriString();

    // ВАЖНО: parse_mode вообще не шлём
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chat_id", resolvedChatId);
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

        log.warn("Telegram send failed (attempt {}): chatId={}, status={}, body={}",
            attempt, resolvedChatId, resp.getStatusCode(), safeTrim(resp.getBody()));
        sleepBackoff(attempt);

      } catch (HttpStatusCodeException e) {
        log.warn("Telegram HTTP error (attempt {}): chatId={}, status={}, body={}",
            attempt, resolvedChatId, e.getStatusCode(), safeTrim(e.getResponseBodyAsString()));
        sleepBackoff(attempt);

      } catch (Exception e) {
        log.warn("Telegram send exception (attempt {}): chatId={}, {}",
            attempt, resolvedChatId, e.getMessage(), e);
        sleepBackoff(attempt);
      }
    }

    log.error("Telegram send окончательно не удалось после {} попыток (chatId={})", maxAttempts, resolvedChatId);
  }

  public void sendPhoto(String chatId, byte[] imageBytes, String caption) {
    String resolvedChatId = resolveChatId(chatId);
    if (resolvedChatId == null || resolvedChatId.isBlank()) {
      log.warn("Telegram chat_id пустой, sendPhoto пропущен");
      return;
    }

    String url = UriComponentsBuilder
        .fromHttpUrl(props.getApiBaseUrl())
        .pathSegment("bot" + props.getBotToken(), "sendPhoto")
        .toUriString();

    ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
      @Override public String getFilename() { return "screenshot.png"; }
    };

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("chat_id", resolvedChatId);
    body.add("photo", imageResource);
    if (caption != null && !caption.isBlank()) body.add("caption", caption);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

    HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

    int maxAttempts = 4;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ResponseEntity<String> resp =
            telegramRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        if (resp.getStatusCode().is2xxSuccessful()) return;
        log.warn("Telegram sendPhoto failed (attempt {}): status={}", attempt, resp.getStatusCode());
        sleepBackoff(attempt);
      } catch (HttpStatusCodeException e) {
        log.warn("Telegram sendPhoto HTTP error (attempt {}): status={}, body={}",
            attempt, e.getStatusCode(), safeTrim(e.getResponseBodyAsString()));
        sleepBackoff(attempt);
      } catch (Exception e) {
        log.warn("Telegram sendPhoto exception (attempt {}): {}", attempt, e.getMessage(), e);
        sleepBackoff(attempt);
      }
    }

    log.error("Telegram sendPhoto окончательно не удалось после {} попыток", maxAttempts);
  }

  private String resolveChatId(String chatId) {
    if (chatId != null && !chatId.isBlank()) return chatId.trim();
    return props.getChatId();
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
