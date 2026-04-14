package com.carus.integrations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class PlaywrightWorker implements CommandLineRunner {

  @Value("${abb.email}") private String email;
  @Value("${abb.password}") private String password;
  @Value("${bc-game.session-cookies:}") private String bcGameCookies;

  private final TelegramSender telegramSender;
  private final ArbHashDeduplicator arbHashDeduplicator;
  private final TelegramProperties tgProps;
  private final WorkerControlService control;
  private final BetExecutorClient betExecutorClient;


  public PlaywrightWorker(TelegramSender telegramSender,
      ArbHashDeduplicator arbHashDeduplicator,
      TelegramProperties tgProps,
      WorkerControlService control,
      BetExecutorClient betExecutorClient) {
    this.telegramSender = telegramSender;
    this.arbHashDeduplicator = arbHashDeduplicator;
    this.tgProps = tgProps;
    this.control = control;
    this.betExecutorClient = betExecutorClient;
  }

  private Playwright pw;
  private Browser browser;
  private BrowserContext context;
  private Page page;

  // Отдельная вкладка для резолва внешних ссылок (тихо, без ухода на букмекера)
  private Page resolverPage;

  // Универсальный capture: поймали внешний document URL -> сюда
  private final AtomicReference<String> externalCapture = new AtomicReference<>();

  // === Settings ===
  private static final Duration NAV_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration LOOP_DELAY = Duration.ofSeconds(5); // скан каждые 5 сек
  private static final Duration RESTART_DELAY = Duration.ofSeconds(10);
  private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(15);

  private static final String ABB_BASE = "https://www.allbestbets.com";

  // Банк под "равную вилку"
  private static final double TOTAL_BANKROLL_USD = 100.0;
  private static final Path SESSION_FILE = Paths.get("/app/session.json");

  @Override
  public void run(String... args) {
    while (true) {
      try {
        // если уже в паузе — точно не держим сессию
        if (control.isPaused()) safeClose();
        while (control.isPaused()) Thread.sleep(1000);

        startBrowser();
        if (!tryOpenArbsWithSession()) {
          login();
          saveSession();
          openArbsPage();
        }

        while (true) {
          if (control.isPaused()) {
            safeClose();                 // ✅ отпускаем ABB-сессию
            while (control.isPaused()) Thread.sleep(1000);
            break;                       // после resume — стартанём заново
          }

          if (control.consumeRestart()) {
            safeClose();
            break;                       // перезапуск цикла (browser+login заново)
          }

          scanArbsOnce();
          page.waitForTimeout(LOOP_DELAY.toMillis());
        }

      } catch (Exception e) {
        System.out.println("[CRASH] Worker crashed, restarting in " + RESTART_DELAY.toSeconds() + "s. Cause: " + e);
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

    System.out.println("Login successful: " + page.url());

    try {
      byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
      telegramSender.sendPhoto("-1003365303378", screenshot, "✅ Login successful");
    } catch (Exception e) {
      System.out.println("Screenshot/TG send failed: " + e.getMessage());
    }
  }

  /** Пробуем открыть arbs с сохранённой сессией. Возвращает true если сессия жива. */
  private boolean tryOpenArbsWithSession() {
    if (!Files.exists(SESSION_FILE)) {
      System.out.println("[session] No saved session, will login");
      return false;
    }
    try {
      page.navigate(
          "https://www.allbestbets.com/arbs",
          new Page.NavigateOptions()
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(NAV_TIMEOUT.toMillis())
      );
      if (page.url().contains("/users/sign_in")) {
        System.out.println("[session] Session expired, will re-login");
        return false;
      }
      System.out.println("[session] Session valid, skipped login: " + page.url());
      try {
        byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        telegramSender.sendPhoto("-1003365303378", screenshot, "✅ Resumed with saved session");
      } catch (Exception e) {
        System.out.println("Screenshot/TG send failed: " + e.getMessage());
      }
      return true;
    } catch (Exception e) {
      System.out.println("[session] Session check failed: " + e.getMessage());
      return false;
    }
  }

  private void saveSession() {
    try {
      context.storageState(new BrowserContext.StorageStateOptions().setPath(SESSION_FILE));
      System.out.println("[session] Session saved to " + SESSION_FILE);
    } catch (Exception e) {
      System.out.println("[session] Failed to save session: " + e.getMessage());
    }
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
    //System.out.println("Found " + n + " arbs");

    for (int i = 0; i < n; i++) {
      Locator arb = arbs.nth(i);

      ArbHeader header = readHeader(arb);
      String arbHash = extractArbHash(arb);

      List<BetLine> betLines = readBets(arb);

      if (shouldSendToTelegram(arbHash)) {

        // 1) считаем "равную вилку" на банк 200$
        double[] stakes = calcEqualStakesUsd(betLines, TOTAL_BANKROLL_USD);

        // 2) резолвим внешние ссылки ДЛЯ ОБЕИХ контор
        List<BetLine> resolved = resolveAllExternalUrls(betLines);

        String message = buildTelegramMessage(header, resolved, arbHash, stakes);

        // ✅ chatId берём из конфига tg.*
        String chatId = selectChatId(resolved);
        telegramSender.sendText(chatId, message);

        System.out.println(">>> SEND TO TG: " + arbHash + " | " + header.updatedAt);

        // отправляем Stake-ставку на экзакьютор (не влияет на TG-поток)
        sendToExecutorIfStake(resolved, stakes);
      }
    }
  }

  private void sendToExecutorIfStake(List<BetLine> betLines, double[] stakes) {
    if (betLines == null) return;
    for (int i = 0; i < betLines.size(); i++) {
      BetLine b = betLines.get(i);
      if (!isBook(b.book, "stake")) continue;

      if (b.resolvedUrl == null || b.resolvedUrl.isBlank()) {
        System.out.println("[executor] Stake bet found but no resolved URL, skipping");
        return;
      }

      String event = betLines.get(0).event;
      BigDecimal odds = parseOddBD(b.odd);
      BigDecimal amount = (stakes != null && i < stakes.length)
          ? BigDecimal.valueOf(stakes[i]).setScale(2, RoundingMode.HALF_UP)
          : null;

      if (odds == null || amount == null) {
        System.out.println("[executor] Cannot parse odds/amount for Stake bet, skipping");
        return;
      }

      betExecutorClient.sendBetSignal(event, b.market, odds, amount, b.resolvedUrl);
      return;
    }
  }

  private BigDecimal parseOddBD(String oddText) {
    Double d = parseOdd(oddText);
    return d == null ? null : BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
  }

  private ArbHeader readHeader(Locator arb) {
    String percent = safeText(arb.locator(".header .percent"));
    String percentClass = safeAttr(arb.locator(".header .percent"), "class");
    String sport = safeText(arb.locator(".header .sport-name"));

    String period = safeText(arb.locator(".header .arb-game-period span"));
    if (period.isBlank()) {
      period = safeText(arb.locator(".header .period-name"));
    }

    String updatedAt = safeText(arb.locator(".header .updated-at"));

    return new ArbHeader(percent, percentClass, sport, period, updatedAt);
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

    // ✅ ссылка может быть на BET-кнопке или на коэффициенте (a.coefficient-link)
    String href = safeAttr(bet.locator(".bet-button a"), "href");

    if (href == null || href.isBlank()) {
      href = safeAttr(bet.locator("a.coefficient-link"), "href");
    }
    if (href == null || href.isBlank()) {
      href = safeAttr(bet.locator(".market a"), "href");
    }

    String abbBetUrl = toAbsAbbUrl(href);

    String depth = null;
    Locator depthLoc = bet.locator(".market-dept");
    if (depthLoc.count() > 0) depth = safeText(depthLoc);

    return new BetLine(book, date, event, league, market, odd, depth, abbBetUrl, null);
  }

  private String toAbsAbbUrl(String href) {
    if (href == null) return null;
    String h = href.trim();
    if (h.isEmpty()) return null;
    if (h.startsWith("http://") || h.startsWith("https://")) return h;
    if (h.startsWith("/")) return ABB_BASE + h;
    return ABB_BASE + "/" + h;
  }

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
  // Equal stake calc
  // =========================

  private double[] calcEqualStakesUsd(List<BetLine> betLines, double total) {
    if (betLines == null || betLines.size() < 2) return null;

    Double o1 = parseOdd(betLines.get(0).odd);
    Double o2 = parseOdd(betLines.get(1).odd);
    if (o1 == null || o2 == null || o1 <= 1.0 || o2 <= 1.0) return null;

    double inv1 = 1.0 / o1;
    double inv2 = 1.0 / o2;
    double sum = inv1 + inv2;
    if (sum <= 0) return null;

    double s1 = total * inv1 / sum;
    s1 = round2(s1);
    double s2 = round2(total - s1);

    return new double[] { s1, s2 };
  }

  private Double parseOdd(String oddText) {
    if (oddText == null) return null;
    String s = oddText.trim();
    if (s.isEmpty()) return null;
    s = s.replace(",", ".").replaceAll("[^0-9.]", "");
    if (s.isEmpty()) return null;
    try {
      return Double.parseDouble(s);
    } catch (Exception e) {
      return null;
    }
  }

  private double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  // =========================
  // Universal external resolver (ALL books)
  // =========================

  private List<BetLine> resolveAllExternalUrls(List<BetLine> betLines) {
    if (betLines == null || betLines.isEmpty()) return betLines;

    List<BetLine> out = new ArrayList<>(betLines.size());
    for (BetLine b : betLines) {
      String resolved = resolveExternalUrlFromAbbBetUrl(b.abbBetUrl);
      resolved = normalizeResolvedUrl(b.book, resolved);

      out.add(new BetLine(
          b.book, b.date, b.event, b.league, b.market, b.odd, b.depth, b.abbBetUrl, resolved
      ));
    }
    return out;
  }

  private String resolveExternalUrlFromAbbBetUrl(String abbBetUrl) {
    if (abbBetUrl == null || abbBetUrl.isBlank() || resolverPage == null) return null;

    externalCapture.set(null);
    System.out.println("[resolver] navigating to ABB url: " + abbBetUrl);

    try {
      resolverPage.navigate(
          abbBetUrl,
          new Page.NavigateOptions()
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(RESOLVE_TIMEOUT.toMillis())
      );
    } catch (PlaywrightException ignored) {}

    long end = System.currentTimeMillis() + RESOLVE_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < end) {
      String got = externalCapture.get();
      if (got != null && !got.isBlank()) return got;
      try { resolverPage.waitForTimeout(100); } catch (Exception ignored) {}
    }

    return externalCapture.get();
  }

  /** Парсит строку вида "name1=val1; name2=val2" в список Playwright Cookie для домена. */
  private List<Cookie> parseCookieString(String raw, String domain) {
    List<Cookie> result = new ArrayList<>();
    for (String part : raw.split(";")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) continue;
      int eq = trimmed.indexOf('=');
      if (eq < 0) continue;
      String name = trimmed.substring(0, eq).trim();
      String value = trimmed.substring(eq + 1).trim();
      result.add(new Cookie(name, value).setDomain(domain).setPath("/"));
    }
    return result;
  }

  private boolean isAbbUrl(String url) {
    if (url == null) return false;
    String u = url.toLowerCase();
    return u.contains("allbestbets.com");
  }

  private String normalizeResolvedUrl(String book, String url) {
    if (url == null || url.isBlank()) return url;

    return url;
  }

  // =========================
  // Telegram message
  // =========================

  private String buildTelegramMessage(
      ArbHeader h,
      List<BetLine> bets,
      String arbHash,
      double[] stakes
  ) {
    String emoji = headerEmoji(h.percentClass);

    String event = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).event);
    String league = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).league);
    String date = bets.isEmpty() ? "" : nullToEmpty(bets.get(0).date);
    String period = nullToEmpty(h.period);

    StringBuilder sb = new StringBuilder();
    sb.append("⚡️ ").append(emoji).append(" ").append(nullToEmpty(h.percent)).append(" | ")
        .append(nullToEmpty(h.sport));

    if (!period.isBlank()) sb.append(" ").append(period);
    sb.append(" | ").append(nullToEmpty(h.updatedAt)).append("\n");

    if (!event.isBlank()) sb.append("🏟 ").append(event).append("\n");
    if (!league.isBlank()) sb.append("🏷 ").append(league).append("\n");
    if (!date.isBlank()) sb.append("🗓 ").append(date).append("\n");

    for (int i = 0; i < bets.size(); i++) {
      BetLine b = bets.get(i);
      sb.append(i + 1).append(") ")
          .append(nullToEmpty(displayBook(b.book))).append(" — ")
          .append(nullToEmpty(b.market)).append(" @ ")
          .append(nullToEmpty(b.odd));

      if (stakes != null && i < stakes.length) {
        sb.append(" | $").append(String.format(java.util.Locale.US, "%.2f", stakes[i]));
      }

      if (b.depth != null && !b.depth.isBlank()) sb.append(" | depth ").append(b.depth);
      sb.append("\n");
    }

    // ✅ ссылки на обе конторы (и любые другие)
    for (BetLine b : bets) {
      if (b.resolvedUrl != null && !b.resolvedUrl.isBlank()) {
        sb.append("🎯 ").append(nullToEmpty(displayBook(b.book))).append(": ").append(b.resolvedUrl).append("\n");
      }
    }

    return sb.toString().trim();
  }

  private String displayBook(String book) {
    // точку меняем на пробел, лишние пробелы схлопываем
    return nullToEmpty(book).replace('.', ' ').replaceAll("\\s+", " ").trim();
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
  // Filtering / Dedup
  // =========================


  private boolean shouldSendToTelegram(String arbHash) {
    if (arbHash == null || arbHash.isBlank()) return false;
    return arbHashDeduplicator.tryAcquire(arbHash);
  }

  // =========================
  // Playwright lifecycle
  // =========================

  private void startBrowser() {
    pw = Playwright.create();

    browser = pw.chromium().launch(new BrowserType.LaunchOptions()
        .setHeadless(true)
        .setArgs(List.of(
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu"
        )));

    if (Files.exists(SESSION_FILE)) {
      System.out.println("[session] Loading saved session from " + SESSION_FILE);
      context = browser.newContext(new Browser.NewContextOptions().setStorageStatePath(SESSION_FILE));
    } else {
      context = browser.newContext();
    }

    page = context.newPage();
    resolverPage = context.newPage();

    if (bcGameCookies != null && !bcGameCookies.isBlank()) {
      List<Cookie> cookies = parseCookieString(bcGameCookies, "bc.game");
      context.addCookies(cookies);
      System.out.println("[bc.game] Loaded " + cookies.size() + " session cookies");
    }

    page.onConsoleMessage(m -> System.out.println("[console] " + m.text()));
    page.onRequestFailed(r -> System.out.println("[request failed] " + r.url()));
    page.onResponse(r -> {
      if (r.status() >= 400) System.out.println("[response " + r.status() + "] " + r.url());
    });

    // Универсальный перехват: как только resolverPage уходит на внешний document — сохраняем и abort
    resolverPage.route("**/*", route -> {
      String url = route.request().url();
      String type = route.request().resourceType();

      if ("document".equals(type)) {
        if (url != null && !isAbbUrl(url)) {
          System.out.println("[resolver] document redirect -> " + url);
          externalCapture.compareAndSet(null, url);
          route.abort();
          return;
        }
      }

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
      if (loc == null || loc.count() == 0) return "";
      String t = loc.first().textContent(new Locator.TextContentOptions().setTimeout(0));
      return t == null ? "" : t.trim();
    } catch (PlaywrightException e) {
      return "";
    }
  }

  private String safeAttr(Locator loc, String name) {
    try {
      if (loc == null || loc.count() == 0) return null;
      String v = loc.first().getAttribute(name, new Locator.GetAttributeOptions().setTimeout(0));
      return v == null ? null : v.trim();
    } catch (PlaywrightException e) {
      return null;
    }
  }

  // ✅ теперь чат берём из tg.* конфига
  private String selectChatId(List<BetLine> betLines) {
    String chatId;

    System.out.println(betLines.get(0).book + " " + betLines.get(1).book);
    if (hasBook(betLines, "game")) {
      chatId = tgProps.getBcGameChatId();
    } else if (hasBook(betLines, "pinnacle") && hasBook(betLines, "stake")) {
      chatId = tgProps.getPinnacleStakeOnlyChatId();
    } else {
      chatId = tgProps.getAllOthersChatId();
    }

    // fallback
    if (chatId == null || chatId.isBlank()) {
      chatId = (tgProps.getChatId() != null && !tgProps.getChatId().isBlank())
          ? tgProps.getChatId()
          : "-1";
    }
    return chatId;
  }

  private boolean hasBook(List<BetLine> betLines, String needle) {
    if (betLines == null || betLines.isEmpty()) return false;
    for (BetLine b : betLines) {
      if (isBook(b.book, needle)) return true;
    }
    return false;
  }

  private boolean isBook(String book, String needle) {
    return book != null && book.toLowerCase().contains(needle);
  }

  // =========================
  // DTOs
  // =========================

  private record ArbHeader(String percent, String percentClass, String sport, String period, String updatedAt) {}

  private record BetLine(
      String book,
      String date,
      String event,
      String league,
      String market,
      String odd,
      String depth,
      String abbBetUrl,
      String resolvedUrl
  ) {}
}
