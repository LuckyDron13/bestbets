package com.carus.integrations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "tg")
public class TelegramProperties {

  private String apiBaseUrl;
  private String botToken;

  /** дефолтный чат (для sendText(text) без chatId) */
  private String chatId;

  /** чат только для вилок с Pinnacle */
  private String pinnacleStakeOnlyChatId;

  /** чат для всех остальных (без Pinnacle и без bc.game) */
  private String allOthersChatId;

  /** чат для любых вилок с bc.game */
  private String bcGameChatId;
}
