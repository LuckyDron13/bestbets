package com.carus.integrations;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tg")
public class TelegramProperties {

  /**
   * Например: 123456789:AA.... (НЕ светить в логах)
   */
  private String botToken;

  /**
   * Может быть "59395" или "-1001234567890" (для групп/каналов).
   * Лучше хранить как String.
   */
  private String chatId;

  /**
   * Можно не трогать.
   */
  private String apiBaseUrl = "https://api.telegram.org";
}
