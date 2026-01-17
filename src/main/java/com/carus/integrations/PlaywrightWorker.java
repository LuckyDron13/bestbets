package com.carus.integrations;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PlaywrightWorker implements CommandLineRunner {

  @Value("${abb.email}") private String email;
  @Value("${abb.password}") private String password;

  private final TelegramSender telegramSender;

  public PlaywrightWorker(TelegramSender telegramSender) {
    this.telegramSender = telegramSender;
  }

  private Playwright pw;
  private Browser browser;
  private BrowserContext context;
  private Page page;

  // Отдельная вкладка для резолва Stake-ссылки (тихо, без ухода на stake)
  private Page resolverPage;
  private final AtomicReference<String> stakeCapture = new AtomicReference<>();

  // === Settings ===
  private static final Duration NAV_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration LOOP_DELAY = Duration.ofSeconds(10);
  private static final Duration RESTART_DELAY = Duration.ofSeconds(10);

  private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(15);

  private static final String ABB_BASE = "https://www.allbestbets.com";

  // Отправляем только если "сек" + антиспам по arb_hash
  private static final long SEND_COOLDOWN_MS = 70_000;
  private static final int MAX_SENT_CACHE = 50_000;

  // LRU: arb_hash -> lastSentAtMs
  private final Map<String, Long> lastSentAtMs = new LinkedHashMap<>(16, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
      return size() > MAX_SENT_CACHE;
    }
  };

  @Override
  public void run(String... args) {
    while (true) {
      try {
        startBrowser();
        login();
        openArbsPage();

        while (true) {
          scanArbsOnce();
          page.waitForTimeout(LOOP_DELAY.toMillis());
        }

      } catch (Exception e) {
        System.err.println("Worker error: " + e.getMessage());
        e.printStackTrace();
        safeClose();
        try { Thread.sleep(RESTART_DELAY.toMillis()); } catch (InterruptedException ignored) {}
      }
    }
  }

  // =========================
  // Navigation / Login
  // =========================

  private void openArbsPage() {
    page.waitForTimeout(10_000);
    page.navigate(
        "https://www.allbestbets.com/arbs",
        new Page.NavigateOptions()
            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            .setTimeout(NAV_TIMEOUT.toMillis())
    );
  }

  private void login() {
    page.navigate(
        "https://www.allbestbets.com/users/sign_in",
        new Page.NavigateOptions()
            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            .setTimeout(NAV_TIMEOUT.toMillis())
    );

    page.waitForSelector("input[name='allbestbets_user[email]']");
    page.waitForSelector("input[name='allbestbets_user[password]']");

    page.waitForTimeout(10_000);
    page.fill("input[name='allbestbets_user[email]']", email);

    page.waitForTimeout(10_000);
    page.fill("input[name='allbestbets_user[password]']", password);

    page.waitForTimeout(10_000);
    page.locator("button[type='submit'], input[type='submit']").first().click();

    page.waitForLoadState(LoadState.DOMCONTENTLOADED);

    try {
      page.waitForURL(
          url -> !url.contains("/users/sign_in"),
          new Page.WaitForURLOptions()
              .setTimeout(NAV_TIMEOUT.toMillis())
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
      );
    } catch (PlaywrightException ignored) {}

    System.out.println("Logged in: " + page.url());
  }

  // =========================
  // Scraping
  // =========================

  private void scanArbsOnce() {
    Locator arbs = page.locator("#arbs-list ul.arbs-list > li.wrapper.arb.has-2-bets");
    int n = arbs.count();
    System.out.println("arbs on page = " + n);

    for (int i = 0; i < n; i++) {
      Locator arb = arbs.nth(i);

      ArbHeader header = readHeader(arb);
      String arbHash = extractArbHash(arb);

      // читаем ставки (сохраняем, чтобы и в TG отправить)
      List<BetLine> betLines = readBets(arb);

      // ✅ отправка только если "сек"
      if (shouldSendToTelegram(arbHash, header.updatedAt)) {
        // резолвим stake только перед отправкой
        String stakeUrl = resolveStakeUrlFromBetLines(betLines);

        String message = buildTelegramMessage(header, betLines, arbHash, stakeUrl);
        telegramSender.sendText(message);
        System.out.println(">>> SEND TO TG: " + arbHash + " | " + header.updatedAt);
      }
    }
  }

  private ArbHeader readHeader(Locator arb) {
    String percent = safeText(arb.locator(".header .percent"));
    String percentClass = safeAttr(arb.locator(".header .percent"), "class");
    String sport = safeText(arb.locator(".header .sport-name"));
    String updatedAt = safeText(arb.locator(".header .updated-at")); // "17 сек" / "2 минуты" / ...

    return new ArbHeader(percent, percentClass, sport, updatedAt);
  }

  private List<BetLine> readBets(Locator arb) {
    Locator bets = arb.locator(".bet-wrapper");
    int count = bets.count();

    List<BetLine> out = new ArrayList<>(Math.min(count, 3));
    for (int j = 0; j < count; j++) {
      out.add(readBet(bets.nth(j)));
    }
    return out;
  }

  private BetLine readBet(Locator bet) {
    String book = safeText(bet.locator(".bookmaker-name a"));
    String date = safeText(bet.locator(".date"));
    String event = safeText(bet.locator(".event-name .name a"));
    String league = safeText(bet.locator(".event-name .league"));
    String market = safeText(bet.locator(".market a span"));
    String odd = safeText(bet.locator("a.coefficient-link"));

    // ссылка на ABB /bets/... (обычно есть в ".market a" или "a.coefficient-link")
    String href = safeAttr(bet.locator(".market a"), "href");
    if (href == null || href.isBlank()) {
      href = safeAttr(bet.locator("a.coefficient-link"), "href");
    }
    String abbBetUrl = toAbsAbbUrl(href);

    String depth = null;
    Locator depthLoc = bet.locator(".market-dept");
    if (depthLoc.count() > 0) depth = safeText(depthLoc);

    return new BetLine(book, date, event, league, market, odd, depth, abbBetUrl);
  }

  private String toAbsAbbUrl(String href) {
    if (href == null) return null;
    String h = href.trim();
    if (h.isEmpty()) return null;
    if (h.startsWith("http://") || h.startsWith("https://")) return h;
    if (h.startsWith("/")) return ABB_BASE + h;
    return ABB_BASE + "/" + h;
  }

  /**
   * Идентификатор вилки — берём arb_hash из href любой ссылки внутри li:
   * ...&arb_hash=fa7997516290f57f63c3afddf3670980
   */
  private String extractArbHash(Locator arb) {
    Locator anyLink = arb.locator("a[href*='arb_hash=']").first();
    String href = anyLink.getAttribute("href");
    if (href == null) return null;

    String key = "arb_hash=";
    int idx = href.indexOf(key);
    if (idx < 0) return null;

    String tail = href.substring(idx + key.length());
    int amp = tail.indexOf('&');
    return (amp >= 0) ? tail.substring(0, amp) : tail;
  }

  // =========================
  // Stake link resolver
  // =========================

  /**
   * Берём ABB bet-url именно для линии Stake и резолвим конечный stake.com/sports/...,
   * не давая реально уйти на stake (document abort).
   */
  private String resolveStakeUrlFromBetLines(List<BetLine> betLines) {
    if (betLines == null || betLines.isEmpty()) return null;

    // пробуем сначала ставки, где book = Stake
    for (BetLine b : betLines) {
      if (b.book != null && b.book.toLowerCase().contains("stake")) {
        String url = resolveStakeUrlFromAbbBetUrl(b.abbBetUrl);
        if (url != null && !url.isBlank()) return url;
      }
    }

    // если почему-то book не “Stake”, но есть abbBetUrl — можно попытаться с первой
    for (BetLine b : betLines) {
      if (b.abbBetUrl != null && !b.abbBetUrl.isBlank()) {
        String url = resolveStakeUrlFromAbbBetUrl(b.abbBetUrl);
        if (url != null && !url.isBlank()) return url;
      }
    }

    return null;
  }

  private String resolveStakeUrlFromAbbBetUrl(String abbBetUrl) {
    if (abbBetUrl == null || abbBetUrl.isBlank() || resolverPage == null) return null;

    stakeCapture.set(null);

    try {
      resolverPage.navigate(
          abbBetUrl,
          new Page.NavigateOptions()
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(RESOLVE_TIMEOUT.toMillis())
      );
    } catch (PlaywrightException ignored) {
      // если во время навигации случится abort/редирект — это ок
    }

    // ждём, пока сработает редирект до stake (server/js) и мы поймаем document-request
    long end = System.currentTimeMillis() + RESOLVE_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < end) {
      String got = stakeCapture.get();
      if (got != null && !got.isBlank()) return got;
      try { resolverPage.waitForTimeout(100); } catch (Exception ignored) {}
    }

    return stakeCapture.get();
  }

  // =========================
  // Telegram message
  // =========================

  private String buildTelegramMessage(ArbHeader h, List<BetLine> bets, String arbHash, String stakeUrl) {
    String emoji = headerEmoji(h.percentClass);

    String event = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).event);
    String league = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).league);
    String date = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).date);

    StringBuilder sb = new StringBuilder();
    sb.append("⚡️ ").append(emoji).append(" ").append(nullToEmpty(h.percent)).append(" | ")
        .append(nullToEmpty(h.sport)).append(" | ").append(nullToEmpty(h.updatedAt)).append("\n");

    if (!event.isBlank()) sb.append("🏟 ").append(event).append("\n");
    if (!league.isBlank()) sb.append("🏷 ").append(league).append("\n");
    if (!date.isBlank()) sb.append("🗓 ").append(date).append("\n");

    for (int i = 0; i < bets.size(); i++) {
      BetLine b = bets.get(i);
      sb.append(i + 1).append(") ")
          .append(nullToEmpty(b.book)).append(" — ")
          .append(nullToEmpty(b.market)).append(" @ ")
          .append(nullToEmpty(b.odd));
      if (b.depth != null && !b.depth.isBlank()) sb.append(" | depth ").append(b.depth);
      sb.append("\n");
    }

    // ✅ только чистая stake-ссылка (одна)
    if (stakeUrl != null && !stakeUrl.isBlank()) {
      sb.append("🎯 ").append(stakeUrl).append("\n");
    }

    if (arbHash != null && !arbHash.isBlank()) {
      sb.append("id: ").append(arbHash);
    }

    return sb.toString().trim();
  }

  private String headerEmoji(String percentClass) {
    String c = (percentClass == null ? "" : percentClass).toLowerCase();
    if (c.contains("green")) return "🟢";
    if (c.contains("red")) return "🔴";
    if (c.contains("yellow")) return "🟡";
    return "⚪️";
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s.trim();
  }

  // =========================
  // Filtering / Anti-spam
  // =========================

  private boolean isSecondsUpdated(String updatedAt) {
    if (updatedAt == null) return false;
    String s = updatedAt.toLowerCase().trim();
    // ловит "17 сек", "2 секунды", "55 секунд", "сек."
    return s.contains("сек");
  }

  private boolean shouldSendToTelegram(String arbHash, String updatedAt) {
    if (arbHash == null || arbHash.isBlank()) return false;
    if (!isSecondsUpdated(updatedAt)) return false;

    long now = System.currentTimeMillis();
    Long last = lastSentAtMs.get(arbHash);

    if (last != null && (now - last) < SEND_COOLDOWN_MS) {
      return false;
    }

    lastSentAtMs.put(arbHash, now);
    return true;
  }

  // =========================
  // Playwright lifecycle
  // =========================

  private void startBrowser() {
    pw = Playwright.create();

    browser = pw.chromium().launch(new BrowserType.LaunchOptions()
        .setHeadless(false));

    context = browser.newContext();

    page = context.newPage();
    resolverPage = context.newPage();

    // === listeners on main page ===
    page.onConsoleMessage(m -> System.out.println("[console] " + m.text()));
    page.onRequestFailed(r -> System.out.println("[request failed] " + r.url()));
    page.onResponse(r -> {
      if (r.status() >= 400) System.out.println("[response " + r.status() + "] " + r.url());
    });

    // === resolver: ловим stake.com document и abort, чтобы не уходить на стейк ===
    resolverPage.route("**/*", route -> {
      String url = route.request().url();
      String type = route.request().resourceType();

      if ("document".equals(type) && url.contains("stake.com")) {
        stakeCapture.compareAndSet(null, url);
        route.abort(); // НЕ даём реально перейти на stake
        return;
      }

      // ускоряем резолв: режем тяжёлое
      if ("image".equals(type) || "font".equals(type) || "media".equals(type)) {
        route.abort();
        return;
      }

      route.resume();
    });

    page.setDefaultTimeout(NAV_TIMEOUT.toMillis());
    page.setDefaultNavigationTimeout(NAV_TIMEOUT.toMillis());

    resolverPage.setDefaultTimeout(RESOLVE_TIMEOUT.toMillis());
    resolverPage.setDefaultNavigationTimeout(RESOLVE_TIMEOUT.toMillis());
  }

  @PreDestroy
  public void onShutdown() {
    safeClose();
  }

  private void safeClose() {
    try { if (context != null) context.close(); } catch (Exception ignored) {}
    try { if (browser != null) browser.close(); } catch (Exception ignored) {}
    try { if (pw != null) pw.close(); } catch (Exception ignored) {}

    context = null;
    browser = null;
    pw = null;
    page = null;
    resolverPage = null;
  }

  // =========================
  // Helpers
  // =========================

  private String safeText(Locator loc) {
    try {
      String t = loc.innerText();
      return t == null ? "" : t.trim();
    } catch (PlaywrightException e) {
      return "";
    }
  }

  private String safeAttr(Locator loc, String name) {
    try {
      String v = loc.getAttribute(name);
      return v == null ? null : v.trim();
    } catch (PlaywrightException e) {
      return null;
    }
  }

  // =========================
  // DTOs
  // =========================

  private record ArbHeader(String percent, String percentClass, String sport, String updatedAt) {}

  private record BetLine(
      String book,
      String date,
      String event,
      String league,
      String market,
      String odd,
      String depth,
      String abbBetUrl
  ) {}
}
