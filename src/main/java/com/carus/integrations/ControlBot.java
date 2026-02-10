package com.carus.integrations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class ControlBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

  private final WorkerControlService control;
  private final TelegramClient tg;
  private final String token;

  public ControlBot(WorkerControlService control,
                    TelegramClient tg,
                    @Value("${tg.bot-token}") String token) {
    this.control = control;
    this.tg = tg;
    this.token = token;
  }

  @Override
  public String getBotToken() {
    return token; // обязательно для стартера
  }

  @Override
  public LongPollingUpdateConsumer getUpdatesConsumer() {
    return this;
  }

  @Override
  public void consume(Update update) {
    if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;

    long chatId = update.getMessage().getChatId();
    String text = update.getMessage().getText().trim();

    switch (text) {
      case "/pause" -> {
        control.pause();
        reply(chatId, "⏸ Pause: воркер отпустит ABB-сессию (закроет браузер).");
      }
      case "/resume" -> {
        control.resume();
        reply(chatId, "▶️ Resume: воркер снова поднимет браузер и залогинится.");
      }
      case "/restart" -> {
        control.restart();
        reply(chatId, "🔁 Restart: воркер пересоздаст Playwright/Browser и перелогинится.");
      }
      case "/status" -> {
        reply(chatId, "paused=" + control.isPaused());
      }
      default -> { /* ignore */ }
    }
  }

  private void reply(long chatId, String msg) {
    try {
      tg.executeAsync(SendMessage.builder()
          .chatId(String.valueOf(chatId))
          .text(msg)
          .build());
    } catch (TelegramApiException e) {
      throw new RuntimeException(e);
    }
  }
}
