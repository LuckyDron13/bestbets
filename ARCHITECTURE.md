# System Architecture & Roadmap

## Что это такое

Система автоматической постановки ставок на основе арбитражных сигналов.
Сигналы приходят из Telegram-каналов, парсятся, и ставки автоматически
размещаются на Stake.com через реальный браузер.

---

## Компоненты

```
┌─────────────────────────────────────────────────────────────┐
│                     Telegram-каналы                         │
│              (арбитражные сигналы, типа @сhannel)           │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    Parser (удалённый сервер)                 │
│                                                             │
│  - Читает TG через userbot (Telethon / TDLib)               │
│  - Парсит текст сигнала: событие, рынок, коэф, сумма        │
│  - Находит URL события на Stake.com                         │
│  - POST /api/bet-signal  →  Bet Executor                    │
└────────────────────────────┬────────────────────────────────┘
                             │  HTTP (Tailscale / LAN / VPS)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│               Bet Executor  (этот проект)                   │
│                    Spring Boot :8081                        │
│                                                             │
│  - Принимает сигнал по REST API                             │
│  - Открывает страницу события через Playwright              │
│  - Находит нужный исход (гандикап, тотал, etc.)             │
│  - Вводит сумму, нажимает Place Bet                         │
│  - Обрабатывает Max Stake / изменение коэфа                 │
│  - Логирует результат                                       │
└────────────────────────────┬────────────────────────────────┘
                             │  HTTP API  localhost:50325
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                       AdsPower                              │
│                                                             │
│  - Управляет браузерными профилями (fingerprint, cookies)   │
│  - Запускает/останавливает браузер по запросу               │
│  - Отдаёт CDP endpoint для подключения Playwright           │
│  - Каждый профиль = отдельный аккаунт Stake                 │
└─────────────────────────────────────────────────────────────┘
```

---

## Как работает один цикл ставки

1. TG-канал публикует сигнал: событие, рынок, коэф, сумма
2. Parser парсит сообщение и отправляет `POST /api/bet-signal`
3. Executor принимает сигнал (HTTP 202), кладёт в очередь
4. Executor просит AdsPower запустить браузер нужного профиля
5. Playwright подключается к браузеру по CDP
6. Открывается страница события на Stake.com
7. Проверяется актуальный коэф (допуск ±0.05)
8. Executor скроллит к исходу, кликает, вводит сумму
9. Если Stake говорит «Max Stake» — принимает скорректированную сумму
10. Ставка размещена, результат логируется

---

## Текущее состояние (что реализовано)

### Bet Executor
- [x] REST API (`POST /api/bet-signal`, `GET /api/health`)
- [x] Интеграция с AdsPower (запуск/статус/остановка браузера)
- [x] Подключение к браузеру через CDP (Playwright)
- [x] Retry при зависшей CDP-сессии (перезапуск браузера)
- [x] Навигация на страницу события
- [x] Клик по исходу (Asian Handicap)
- [x] Ввод суммы ставки
- [x] Проверка коэффициента с допуском
- [x] Обработка Max Stake — принять скорректированную сумму
- [x] Human-like поведение (scroll + random delay 1-2s)
- [x] Скриншоты при ошибках
- [x] Асинхронная обработка сигналов

### Что ещё не сделано
- [ ] Поддержка тоталов (Over/Under) и Match Result
- [ ] Несколько профилей параллельно
- [ ] Уведомления об успехе/ошибке обратно в TG
- [ ] Статистика ставок (БД / файл)
- [ ] Веб-дашборд / мониторинг
- [ ] Автоматический пересчёт суммы при изменении коэфа

---

## Деплой на Windows VPS

### Требования к VPS
- OS: Windows Server 2019/2022
- RAM: минимум 4 GB (лучше 8 GB — браузеры прожорливые)
- CPU: 2+ ядра
- Диск: 50 GB SSD

### Шаги

**1. Установить AdsPower**

Скачать с [adspower.com](https://www.adspower.com), установить, залогиниться,
импортировать профили с аккаунтами Stake.

**2. Собрать JAR**
```bash
./mvnw package -DskipTests
```

**3. Переносим на VPS**

Кидаем `target/bet-executor-*.jar` на VPS (через RDP / WinSCP / scp).

**4. Запустить как сервис через NSSM**

Скачать [NSSM](https://nssm.cc), затем в cmd от администратора:
```bash
nssm install BetExecutor "java" "-jar C:\bet-executor\app.jar"
nssm set BetExecutor AppDirectory C:\bet-executor
nssm start BetExecutor
```

Сервис будет подниматься автоматически при перезагрузке.

**5. Открыть порт в файрволе**
```
Панель управления → Брандмауэр →
Правила для входящих → Новое правило → Порт → TCP 8081
```

**6. Парсер стучит на:**
```
http://<VPS_IP>:8081/api/bet-signal
```

---

## Как масштабировать

### Больше аккаунтов Stake

Каждый аккаунт = профиль в AdsPower.
Добавить профили в `application.yaml`:

```yaml
adspower:
  profiles:
    - id: "k192kriw"
      name: "Account 1"
    - id: "abc12345"
      name: "Account 2"
```

Executor будет распределять сигналы по профилям (пока round-robin, можно сделать
по балансу или лимитам).

### Больше букмекеров

Parser уже отправляет универсальный сигнал (событие, рынок, коэф, сумма).
Достаточно добавить новый executor под другого букмекера с тем же API-контрактом.
Parser просто шлёт тот же запрос на другой адрес.

### Надёжность

- Добавить очередь сигналов (Redis / RabbitMQ) — чтоб не терять сигналы при рестарте
- Retry при ошибках сети
- Алерты в Telegram при падении сервиса

### Аналитика

- Писать результаты ставок в PostgreSQL
- Считать ROI, win rate, проблемные рынки
- Дашборд (Grafana или простой веб-интерфейс)

---

## Структура проекта

```
src/
├── api/
│   ├── controller/     # REST endpoints
│   └── dto/            # Request / Response DTOs
├── adspower/           # AdsPower HTTP client (запуск браузера)
├── betting/            # Основная логика: BetProcessingService, StakeBetExecutor
├── browser/            # BrowserSessionManager (CDP, Playwright)
├── config/             # StakeProperties (application.yaml binding)
├── exception/          # GlobalExceptionHandler
└── model/              # BetSignal, MarketType, BetStatus
```

---

## Полезные команды

```bash
# Проверить что сервис живой
curl http://localhost:8081/api/health

# Тестовая ставка вручную
curl -X POST http://localhost:8081/api/bet-signal \
  -H "Content-Type: application/json" \
  -d '{
    "event":    "Halifax Town - Scunthorpe United",
    "market":   "Ф1(0)",
    "odds":     1.79,
    "amount":   12.50,
    "eventUrl": "https://stake.com/sports/soccer/england/national-league/46394822-fc-halifax-town-scunthorpe-united"
  }'

# Логи сервиса (NSSM пишет в AppData)
type C:\Users\<user>\AppData\Local\NSSM\BetExecutor\stdout.log
```
