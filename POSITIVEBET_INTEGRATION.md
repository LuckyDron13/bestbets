# PositiveBet Integration Plan

## Decision: Same Project

Add PositiveBet as a second scanner **inside the existing `bestbets` project**.

**Rationale:**
- `TelegramSender`, `ArbHashDeduplicator`, `BetExecutorClient`, `ControlBot` — all reused as-is
- Single deployment, single Playwright process
- Scanner isolation achieved via independent `@Component` workers with their own try/catch loops
- If PositiveBet scraping breaks → only its worker crashes, AllBestBets continues unaffected

**PositiveBet scope (Phase 1):**
- Login + scrape arbs
- Send TG notifications (same format as AllBestBets)
- No bet-executor integration yet

---

## Required Refactoring

The current codebase is tightly coupled to AllBestBets inside `PlaywrightWorker`.
Before adding a second scanner, extract a shared abstraction.

### 1. Extract `ArbScanner` interface

```java
public interface ArbScanner {
    String getName();          // "allbestbets" | "positivebet"
    void start();
    void stop();
    void restart();
    boolean isRunning();
}
```

### 2. Rename existing worker

`PlaywrightWorker` → `AllBestBetsScanner implements ArbScanner`

Internal logic stays the same. Only the class name and interface change.

### 3. Create `PositiveBetScanner implements ArbScanner`

New class with its own:
- Playwright `Browser` + `Page` lifecycle
- Login flow (positivebet.com credentials)
- Arb parsing logic (DOM TBD — user to provide)
- Scan loop with `LOOP_DELAY` (configurable, start with 10s)

### 4. Update `ControlBot`

Current `/pause`, `/resume`, `/restart` commands target a single worker.
After refactoring, they accept an optional scanner name:

| Command             | Effect                            |
|---------------------|-----------------------------------|
| `/pause`            | Pauses **all** scanners           |
| `/pause abb`        | Pauses AllBestBets only           |
| `/pause pb`         | Pauses PositiveBet only           |
| `/resume [name]`    | Same pattern                      |
| `/restart [name]`   | Same pattern                      |
| `/status`           | Shows status of each scanner      |

If no name given → applies to all (backwards compatible).

---

## New Package Structure

```
src/main/java/com/yourpackage/
├── scanner/
│   ├── ArbScanner.java                  ← new interface
│   ├── allbestbets/
│   │   └── AllBestBetsScanner.java      ← renamed from PlaywrightWorker
│   └── positivebet/
│       ├── PositiveBetScanner.java      ← new
│       └── PositiveBetArbParser.java    ← new (DOM parsing logic)
├── dedup/
│   └── ArbHashDeduplicator.java        ← unchanged
├── telegram/
│   ├── TelegramSender.java             ← unchanged
│   └── ControlBot.java                 ← updated for multi-scanner
├── executor/
│   └── BetExecutorClient.java          ← unchanged
└── model/
    └── ArbOpportunity.java             ← shared model (extract from PlaywrightWorker if not already)
```

---

## Config Changes

Add PositiveBet credentials and toggle to `application-prod.yml`:

```yaml
positivebet:
  enabled: true
  email: your@email.com
  password: yourpassword
  scan-interval-ms: 10000
  url: https://positivebet.com/en/bets/index

# Existing allbestbets config stays unchanged
abb:
  email: ...
  password: ...
```

---

## PositiveBet Scanner — Implementation Steps

Once you provide the DOM structure, implement in this order:

1. **`PositiveBetProperties.java`** — `@ConfigurationProperties(prefix = "positivebet")`
2. **Login flow** — navigate to login page, fill credentials, wait for redirect
3. **`PositiveBetArbParser.java`** — parse arb rows from the bets page:
   - Extract: profit %, sport, event, bookmakers, odds, market, direct URLs
   - Return `List<ArbOpportunity>`
4. **`PositiveBetScanner.java`** — scan loop:
   ```
   while (running) {
       try {
           List<ArbOpportunity> arbs = parser.parseArbs(page);
           for (ArbOpportunity arb : arbs) {
               if (deduplicator.isNew(arb.getHash())) {
                   telegramSender.send(formatMessage(arb));
               }
           }
       } catch (Exception e) {
           log.error("PositiveBet scan error", e);
       }
       Thread.sleep(scanIntervalMs);
   }
   ```
5. **TG message format** — same structure as AllBestBets messages (profit, sport, event, legs with odds + links)

---

## What Is NOT Changing

| Component             | Status       |
|-----------------------|--------------|
| `TelegramSender`      | No changes   |
| `ArbHashDeduplicator` | No changes   |
| `BetExecutorClient`   | No changes   |
| `AllBestBetsScanner`  | Rename only  |
| TG routing by bookie  | No changes   |
| Bet executor flow     | No changes   |

---

## Next Step

Provide the DOM of `https://positivebet.com/en/bets/index` (arb rows HTML) and the login page structure.
That unblocks implementation of `PositiveBetArbParser` and the login flow.
