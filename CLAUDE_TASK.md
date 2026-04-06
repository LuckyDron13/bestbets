# Task: Bet Executor Service — Next Development Stage

## Project Context

You are working on **bet-executor-service** — a Spring Boot microservice that automatically places bets on Stake.com.

**Tech stack:** Java 17, Spring Boot 3.4.4, Playwright 1.49.0, Lombok, Maven  
**Base package:** `com.dron.stake`  
**Port:** 8081

### How the system works
1. A separate **Parser service** finds arbitrage opportunities on betting scanner sites
2. Parser sends a signal to this service via `POST /api/bet-signal`
3. This service opens Stake.com in a browser (via AdsPower + Playwright CDP) and places the bet automatically

### Real signal example from Telegram bot
```
Newells - Club Atletico Acassuso | Argentina. Copa Argentina
1) Stake — Ф1(0) @ 1.36 | $76.06
URL: https://stake.com/sports/soccer/argentina/copa-argentina/46363364-newell-s-old-boys-ca-acassuso
```

---

## Already implemented (DO NOT modify these files)

### `src/main/java/com/dron/stake/model/MarketType.java`
```java
package com.dron.stake.model;

/**
 * Supported bet market types.
 * Parser sends raw strings like "Ф1(0)", "Ф2(+1)" — use fromString() to convert.
 */
public enum MarketType {

    HANDICAP,
    TOTAL_OVER,
    TOTAL_UNDER,
    MONEYLINE,
    BOTH_TEAMS_TO_SCORE,
    UNKNOWN;

    /**
     * Converts a raw market string from the parser into a MarketType.
     *
     * Examples:
     *   "Ф1(0)"  -> HANDICAP
     *   "Ф2(+1)" -> HANDICAP
     *   "OVER"   -> TOTAL_OVER
     */
    public static MarketType fromString(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;

        String v = value.trim().toUpperCase();

        if (v.startsWith("Ф") || v.startsWith("F") || v.contains("HANDICAP")) return HANDICAP;
        if (v.contains("OVER")  || v.contains("БОЛЬШЕ"))                       return TOTAL_OVER;
        if (v.contains("UNDER") || v.contains("МЕНЬШЕ"))                       return TOTAL_UNDER;
        if (v.contains("1X2")   || v.contains("MONEYLINE"))                    return MONEYLINE;
        if (v.contains("BTTS")  || v.contains("BOTH"))                         return BOTH_TEAMS_TO_SCORE;

        return UNKNOWN;
    }
}
```

### `src/main/java/com/dron/stake/model/BetStatus.java`
```java
package com.dron.stake.model;

/**
 * Lifecycle states of a bet from signal receipt to final outcome.
 */
public enum BetStatus {

    PENDING,        // signal received, not yet processed
    IN_PROGRESS,    // browser opened, bet placement in progress
    SUCCESS,        // bet placed successfully
    ODDS_CHANGED,   // odds shifted beyond tolerance — bet cancelled
    FAILED,         // technical failure (timeout, element not found, etc.)
    SKIPPED         // signal did not pass validation — no bet placed
}
```

### `src/main/java/com/dron/stake/model/BetSignal.java`
```java
package com.dron.stake.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain model for an arbitrage bet signal from the Parser.
 * Created once from the incoming DTO and passed through the processing pipeline.
 */
@Value
@Builder
public class BetSignal {

    UUID       id;          // unique signal ID generated on receipt
    String     event;       // e.g. "Newells - Club Atletico Acassuso"
    String     market;      // raw market string from parser: "Ф1(0)", "Ф2(+1)"
    MarketType marketType;  // parsed enum derived from market string
    BigDecimal odds;        // expected odds, e.g. 1.36
    BigDecimal amount;      // bet amount pre-calculated by parser, e.g. 76.06
    String     eventUrl;    // direct Stake event URL
    Instant    receivedAt;  // time the signal arrived
}
```

### `src/main/java/com/dron/stake/api/dto/BetSignalRequest.java`
```java
package com.dron.stake.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Incoming DTO from the Parser via POST /api/bet-signal.
 *
 * Example payload based on real bot messages:
 * {
 *   "event":     "Newells - Club Atletico Acassuso",
 *   "market":    "Ф1(0)",
 *   "odds":      1.36,
 *   "amount":    76.06,
 *   "eventUrl":  "https://stake.com/sports/soccer/..."
 * }
 */
@Data
public class BetSignalRequest {

    @NotBlank(message = "event is required")
    private String event;

    @NotBlank(message = "market is required")
    private String market;      // raw string: "Ф1(0)", "Ф2(+1)", "Ф1(-1)"

    @NotNull(message = "odds is required")
    @DecimalMin(value = "1.01", message = "odds must be >= 1.01")
    @DecimalMax(value = "1000.0", message = "odds must be <= 1000")
    private BigDecimal odds;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    private BigDecimal amount;  // pre-calculated by parser, e.g. 76.06

    @NotBlank(message = "eventUrl is required")
    private String eventUrl;    // https://stake.com/...
}
```

### `src/main/java/com/dron/stake/api/dto/BetSignalResponse.java`
```java
package com.dron.stake.api.dto;

import com.dron.stake.model.BetStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response returned to the Parser after POST /api/bet-signal.
 */
@Value
public class BetSignalResponse {

    UUID       signalId;
    BetStatus  status;
    BigDecimal odds;
    BigDecimal amount;
    String     message;
    Instant    processedAt;

    /**
     * Signal accepted — bet placement will proceed asynchronously.
     */
    public static BetSignalResponse accepted(UUID signalId, BigDecimal odds, BigDecimal amount) {
        return new BetSignalResponse(
                signalId,
                BetStatus.PENDING,
                odds,
                amount,
                "Signal accepted, processing...",
                Instant.now()
        );
    }

    /**
     * Signal rejected — failed validation or unsupported bookmaker.
     */
    public static BetSignalResponse rejected(UUID signalId, String reason) {
        return new BetSignalResponse(
                signalId,
                BetStatus.SKIPPED,
                null,
                null,
                reason,
                Instant.now()
        );
    }
}
```

### `src/main/java/com/dron/stake/api/dto/BetSignalMapper.java`
```java
package com.dron.stake.api.dto;

import com.dron.stake.model.BetSignal;
import com.dron.stake.model.MarketType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps incoming DTO to the internal BetSignal domain model.
 * Static utility — no dependencies, easy to unit test.
 */
public class BetSignalMapper {

    private BetSignalMapper() {}

    public static BetSignal toDomain(BetSignalRequest request) {
        return BetSignal.builder()
                .id(UUID.randomUUID())
                .event(request.getEvent())
                .market(request.getMarket())
                .marketType(MarketType.fromString(request.getMarket()))
                .odds(request.getOdds())
                .amount(request.getAmount())
                .eventUrl(request.getEventUrl())
                .receivedAt(Instant.now())
                .build();
    }
}
```

### `src/main/java/com/dron/stake/api/controller/BetSignalController.java`
```java
package com.dron.stake.api.controller;

import com.dron.stake.api.dto.BetSignalMapper;
import com.dron.stake.api.dto.BetSignalRequest;
import com.dron.stake.api.dto.BetSignalResponse;
import com.dron.stake.model.BetSignal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Entry point for arbitrage signals from the Parser.
 *
 * POST /api/bet-signal  — receive and queue a new bet signal
 * GET  /api/health      — liveness check for the Parser
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BetSignalController {

    // TODO: inject BetProcessingService once implemented
    // private final BetProcessingService betProcessingService;

    @PostMapping("/bet-signal")
    public ResponseEntity<BetSignalResponse> receiveBetSignal(
            @Valid @RequestBody BetSignalRequest request) {

        log.info("Received bet signal: event='{}', market='{}', odds={}, amount={}, url={}",
                request.getEvent(),
                request.getMarket(),
                request.getOdds(),
                request.getAmount(),
                request.getEventUrl());

        BetSignal signal = BetSignalMapper.toDomain(request);

        log.debug("Signal mapped: id={}, marketType={}", signal.getId(), signal.getMarketType());

        // TODO: betProcessingService.process(signal);

        return ResponseEntity.accepted()
                .body(BetSignalResponse.accepted(signal.getId(), signal.getOdds(), signal.getAmount()));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("bet-executor is running");
    }
}
```

### `src/main/resources/application.yaml`
```yaml
server:
  port: 8081

spring:
  application:
    name: bet-executor-service

adspower:
  url: http://local.adspower.net:50325
  profiles:
    - id: "REPLACE_ME"
      name: "stake-main"

stake:
  bet-amount: 10.00
  odds-tolerance: 0.05
  navigation-timeout: 15000
  bet-timeout: 10000

logging:
  screenshots:
    enabled: true
    path: ./screenshots
  level:
    com.dron.stake: DEBUG
```

---

## Your task — implement the following modules in order:

### 1. `AdsPowerService` — `src/main/java/com/dron/stake/adspower/`

Connect to the AdsPower Local API (runs on `http://local.adspower.net:50325`) to manage browser profiles.

**Create these files:**

**`AdsPowerProperties.java`** — `@ConfigurationProperties(prefix = "adspower")`, fields: `url: String`, `profiles: List<ProfileConfig>`. Inner class `ProfileConfig` with `id` and `name`.

**`AdsPowerClient.java`** — low-level HTTP client using `RestTemplate`. Methods:
- `startBrowser(String profileId)` — GET `/api/v1/browser/start?user_id={profileId}`, returns raw JSON response
- `stopBrowser(String profileId)` — GET `/api/v1/browser/stop?user_id={profileId}`
- `checkStatus(String profileId)` — GET `/api/v1/browser/active?user_id={profileId}`

**`AdsPowerResponse.java`** — DTO for AdsPower API response:
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "ws": {
      "puppeteer": "ws://127.0.0.1:xxxx/...",
      "selenium": "http://127.0.0.1:xxxx"
    },
    "debug_port": "xxxx",
    "webdriver": "path/to/chromedriver"
  }
}
```

**`AdsPowerService.java`** — `@Service`, uses `AdsPowerClient`. Methods:
- `getCdpEndpoint(String profileId): String` — starts browser if not running, extracts `data.ws.puppeteer` WebSocket URL for Playwright CDP connection
- `stopBrowser(String profileId): void`

**Important:** AdsPower returns `code: 0` for success. Throw a custom `AdsPowerException` if `code != 0`.

---

### 2. `BrowserSessionManager` — `src/main/java/com/dron/stake/browser/`

Manages Playwright browser connections to AdsPower profiles.

**Create these files:**

**`PlaywrightConfig.java`** — `@Configuration`, creates a `Playwright` bean (singleton, `@Bean`). Important: do NOT use try-with-resources — Spring manages the lifecycle.

**`BrowserSessionManager.java`** — `@Service`, uses `Playwright` bean and `AdsPowerService`. Methods:
- `getOrCreateSession(String profileId): Page` — if session exists return it, otherwise: call `adsPowerService.getCdpEndpoint(profileId)`, connect via `playwright.chromium().connectOverCDP(cdpEndpoint)`, get first page or create new one, store in `Map<String, Page> sessions`
- `closeSession(String profileId): void`
- `closeAllSessions(): void` — call on `@PreDestroy`

---

### 3. `GlobalExceptionHandler` — `src/main/java/com/dron/stake/exception/`

**`AdsPowerException.java`** — runtime exception with message and optional cause.

**`GlobalExceptionHandler.java`** — `@RestControllerAdvice`:
- Handle `MethodArgumentNotValidException` → 400 with field errors map
- Handle `AdsPowerException` → 503 with error message
- Handle `Exception` → 500 with error message
- All responses as `Map<String, Object>` with keys: `status`, `message`, `timestamp`

---

## Rules

- All code and comments in **English only**
- Use `@Slf4j` for logging in every service class
- Use `lombok` everywhere possible (`@Value`, `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`)
- No business logic in controllers — controllers only validate and delegate
- Throw exceptions early, never return null from services
- Follow the existing package structure: `com.dron.stake.*`
