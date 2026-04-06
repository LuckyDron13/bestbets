# Bet Executor — API Integration Guide

Base URL: `http://<host>:8081`

---

## Health check

Before sending signals, verify the service is alive:

```
GET /api/health
```

Response `200 OK`:
```
bet-executor is running
```

---

## Place a bet

```
POST /api/bet-signal
Content-Type: application/json
```

### Request body

| Field      | Type           | Required | Description                                      |
|------------|----------------|----------|--------------------------------------------------|
| `event`    | string         | yes      | Human-readable event name, e.g. `"Halifax - Scunthorpe"` |
| `market`   | string         | yes      | Raw market string — see supported formats below  |
| `odds`     | number (≥1.01) | yes      | Expected odds at the time of the signal          |
| `amount`   | number (≥0.01) | yes      | Stake amount in USDT, pre-calculated by the parser |
| `eventUrl` | string         | yes      | Full Stake.com URL to the event page             |

### Supported `market` values

| Format            | Interpreted as       |
|-------------------|----------------------|
| `Ф1(0)`, `Ф1(-1)` | Asian Handicap, team 1 |
| `Ф2(0)`, `Ф2(+1)` | Asian Handicap, team 2 |
| `OVER`, `БОЛЬШЕ`   | Total — Over         |
| `UNDER`, `МЕНЬШЕ`  | Total — Under        |
| `1X2`, `MONEYLINE` | Match Result         |

### Example request

```json
{
  "event":    "Halifax Town - Scunthorpe United",
  "market":   "Ф1(0)",
  "odds":     1.79,
  "amount":   12.50,
  "eventUrl": "https://stake.com/sports/soccer/england/national-league/46394822-fc-halifax-town-scunthorpe-united"
}
```

### Response `202 Accepted`

The bet is accepted and placed **asynchronously** — the `202` means the signal was received and queued, not that the bet was already placed on the site.

```json
{
  "signalId":    "c2040de7-fa97-44f8-9318-7134d8ab841f",
  "status":      "PENDING",
  "odds":        1.79,
  "amount":      12.50,
  "message":     "Signal accepted, processing...",
  "processedAt": "2026-03-28T12:00:00.000Z"
}
```

---

## Error responses

### 400 Bad Request — validation failed

Returned when a required field is missing or a value is out of range.

```json
{
  "status":    400,
  "message":   { "odds": "odds must be >= 1.01", "eventUrl": "eventUrl is required" },
  "timestamp": "2026-03-28T12:00:00.000Z"
}
```

### 503 Service Unavailable — AdsPower not reachable

```json
{
  "status":    503,
  "message":   "Failed to start browser for profileId=k192kriw: ...",
  "timestamp": "2026-03-28T12:00:00.000Z"
}
```

### 500 Internal Server Error — bet placement failed

```json
{
  "status":    500,
  "message":   "Bet execution failed for signal c2040de7-...",
  "timestamp": "2026-03-28T12:00:00.000Z"
}
```

---

## Notes

- **Odds tolerance**: if the live odds on Stake differ from `odds` by more than `0.05`, the bet is cancelled and logged as `ODDS_CHANGED`. Send the most up-to-date odds you have.
- **Amount**: send the final USDT amount. The executor places exactly that amount. If Stake lowers the max stake, it will automatically accept the adjusted amount and re-place.
- **One bet at a time per profile**: the executor processes signals sequentially per AdsPower profile. Do not flood it with parallel requests for the same profile.
