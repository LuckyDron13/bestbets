package com.carus.integrations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
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

  private final TelegramSender telegramSender;

  public PlaywrightWorker(TelegramSender telegramSender) {
    this.telegramSender = telegramSender;
  }

  private Playwright pw;
  private Browser browser;
  private BrowserContext context;
  private Page page;

  // Отдельная вкладка для резолва внешних ссылок (тихо, без ухода на букмекера)
  private Page resolverPage;
  private final AtomicReference<String> stakeCapture = new AtomicReference<>();

  // === Settings ===
  private static final Duration NAV_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration LOOP_DELAY = Duration.ofSeconds(5); // скан каждую секунду
  private static final Duration RESTART_DELAY = Duration.ofSeconds(10);

  private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(15);

  private static final String ABB_BASE = "https://www.allbestbets.com";

  // Банк под “равную вилку”
  private static final double TOTAL_BANKROLL_USD = 200.0;

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

      List<BetLine> betLines = readBets(arb);

      if (shouldSendToTelegram(arbHash, header.updatedAt)) {

        // 1) считаем “равную вилку” на банк 200$
        double[] stakes = calcEqualStakesUsd(betLines, TOTAL_BANKROLL_USD);

        // 2) резолвим чистую ссылку на Stake (если есть Stake-линия)
        String stakeUrl = resolveStakeUrlFromBetLines(betLines);
        stakeUrl = replaceStakeTo1073(stakeUrl);

        String message = buildTelegramMessage(header, betLines, arbHash, stakes, stakeUrl);
        telegramSender.sendText(message);

        System.out.println(">>> SEND TO TG: " + arbHash + " | " + header.updatedAt);
      }
    }
  }

  private ArbHeader readHeader(Locator arb) {
    String percent = safeText(arb.locator(".header .percent"));
    String percentClass = safeAttr(arb.locator(".header .percent"), "class");
    String sport = safeText(arb.locator(".header .sport-name"));

    // период/карта/тайм: "[2 карта]" / "[с ОТ]" / ...
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

    // ABB /bets/... (для резолва внешнего deep-link)
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
  // Equal stake calc (банк 200$)
  // =========================

  /**
   * “Равная вилка” для 2 исходов:
   * s1 = T*(1/o1) / ((1/o1)+(1/o2))
   * s2 = T - s1
   */
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
    double s2 = round2(total - s1); // чтобы сумма была ровно total после округления

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
  // Stake deep-link resolver
  // =========================

  private String resolveStakeUrlFromBetLines(List<BetLine> betLines) {
    if (betLines == null) return null;
    for (BetLine b : betLines) {
      if (isBook(b.book, "stake")) {
        String url = resolveStakeUrlFromAbbBetUrl(b.abbBetUrl);
        if (url != null && !url.isBlank()) return url;
      }
    }
    return null;
  }

  private boolean isBook(String book, String needle) {
    return book != null && book.toLowerCase().contains(needle);
  }

  private String resolveStakeUrlFromAbbBetUrl(String abbBetUrl) {
    return resolveExternalUrlFromAbbBetUrl(abbBetUrl, stakeCapture);
  }

  private String resolveExternalUrlFromAbbBetUrl(String abbBetUrl, AtomicReference<String> captureRef) {
    if (abbBetUrl == null || abbBetUrl.isBlank() || resolverPage == null) return null;

    // сбрасываем именно ту “цель”, которую хотим поймать
    captureRef.set(null);

    try {
      resolverPage.navigate(
          abbBetUrl,
          new Page.NavigateOptions()
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(RESOLVE_TIMEOUT.toMillis())
      );
    } catch (PlaywrightException ignored) {
      // abort/редиректы могут ронять navigate — это ок
    }

    long end = System.currentTimeMillis() + RESOLVE_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < end) {
      String got = captureRef.get();
      if (got != null && !got.isBlank()) return got;
      try { resolverPage.waitForTimeout(100); } catch (Exception ignored) {}
    }

    return captureRef.get();
  }

  // =========================
  // Telegram message
  // =========================

  private String buildTelegramMessage(
      ArbHeader h,
      List<BetLine> bets,
      String arbHash,
      double[] stakes,
      String stakeUrl
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
          .append(nullToEmpty(b.book)).append(" — ")
          .append(nullToEmpty(b.market)).append(" @ ")
          .append(nullToEmpty(b.odd));

      if (stakes != null && i < stakes.length) {
        sb.append(" | $").append(String.format(java.util.Locale.US, "%.2f", stakes[i]));
      }

      if (b.depth != null && !b.depth.isBlank()) sb.append(" | depth ").append(b.depth);
      sb.append("\n");
    }

    // ✅ только “чистая” внешняя ссылка на Stake, если есть
    if (stakeUrl != null && !stakeUrl.isBlank()) {
      sb.append("🎯 Stake: ").append(stakeUrl).append("\n");
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
    return s.contains("сек");
  }

  private boolean shouldSendToTelegram(String arbHash, String updatedAt) {
    if (arbHash == null || arbHash.isBlank()) return false;
    if (!isSecondsUpdated(updatedAt)) return false;

    long now = System.currentTimeMillis();
    Long last = lastSentAtMs.get(arbHash);

    if (last != null && (now - last) < SEND_COOLDOWN_MS) return false;

    lastSentAtMs.put(arbHash, now);
    return true;
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

    context = browser.newContext();

    page = context.newPage();
    resolverPage = context.newPage();

    // === listeners on main page ===
    page.onConsoleMessage(m -> System.out.println("[console] " + m.text()));
    page.onRequestFailed(r -> System.out.println("[request failed] " + r.url()));
    page.onResponse(r -> {
      if (r.status() >= 400) System.out.println("[response " + r.status() + "] " + r.url());
    });

    // === resolver: ловим stake document и abort, чтобы не уходить на букмекера ===
    resolverPage.route("**/*", route -> {
      String url = route.request().url();
      String type = route.request().resourceType();

      if ("document".equals(type)) {
        if (url.contains("stake.com")) {
          stakeCapture.compareAndSet(null, url);
          route.abort();
          return;
        }
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

  /**
   * Меняет stake.com / www.stake.com -> stake1073.com, сохраняя path/query.
   * Ничего не трогает для других доменов.
   */
  private String replaceStakeTo1073(String url) {
    if (url == null || url.isBlank()) return url;

    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null) return url;

      // уже зеркало
      if (host.equalsIgnoreCase("stake1073.com") || host.equalsIgnoreCase("www.stake1073.com")) {
        return url;
      }

      // меняем только stake.com / www.stake.com
      if (!(host.equalsIgnoreCase("stake.com") || host.equalsIgnoreCase("www.stake.com"))) {
        return url;
      }

      URI replaced = new URI(
          uri.getScheme(),
          uri.getUserInfo(),
          "stake1073.com",
          uri.getPort(),
          uri.getPath(),
          uri.getQuery(),
          uri.getFragment()
      );

      return replaced.toString();
    } catch (Exception e) {
      return url;
    }
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
      String v = loc.getAttribute(name, new Locator.GetAttributeOptions().setTimeout(0));
      return v == null ? null : v.trim();
    } catch (PlaywrightException e) {
      return null;
    }
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
      String abbBetUrl
  ) {}
}
