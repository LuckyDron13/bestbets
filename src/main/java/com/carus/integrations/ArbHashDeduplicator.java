package com.carus.integrations;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ArbHashDeduplicator {

  private static final Logger log = LoggerFactory.getLogger(ArbHashDeduplicator.class);

  private static final Duration RETENTION = Duration.ofDays(2);      // TTL хеша
  private static final Duration CLEANUP_EVERY = Duration.ofHours(6); // как часто чистим

  private final ConcurrentHashMap<String, Long> sentAtMs = new ConcurrentHashMap<>();

  private final ScheduledExecutorService cleaner =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "arb-hash-cleaner");
        t.setDaemon(true);
        return t;
      });

  public ArbHashDeduplicator() {
    cleaner.scheduleAtFixedRate(this::cleanupSafe,
        CLEANUP_EVERY.toMillis(),
        CLEANUP_EVERY.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /**
   * @return true если этот arb_hash можно отправить сейчас.
   *         false если это дубль (еще не протух по TTL).
   */
  public boolean tryAcquire(String arbHash) {
    if (arbHash == null || arbHash.isBlank()) return false;

    final long now = System.currentTimeMillis();
    final long ttlMs = RETENTION.toMillis();

    final AtomicBoolean allowed = new AtomicBoolean(false);
    final AtomicBoolean duplicate = new AtomicBoolean(false);
    final AtomicBoolean expired = new AtomicBoolean(false);

    sentAtMs.compute(arbHash, (k, oldTs) -> {
      if (oldTs == null) {
        allowed.set(true);
        return now;
      }
      long age = now - oldTs;
      if (age > ttlMs) {
        allowed.set(true);
        expired.set(true);
        return now;
      }
      duplicate.set(true);
      return oldTs;
    });

    if (duplicate.get()) {
      Long last = sentAtMs.get(arbHash);
      long ageSec = (last == null) ? -1 : (now - last) / 1000;
      log.debug("DUPLICATE arb_hash={}, age={}s (skip)", arbHash, ageSec);
      //System.out.printf("DUPLICATE arb_hash=%s, age=%s (skip)%n", arbHash, ageSec);

    } else if (expired.get()) {
      log.info("arb_hash expired -> allow again: {}", arbHash);
    }

    return allowed.get();
  }

  public int size() {
    return sentAtMs.size();
  }

  private void cleanupSafe() {
    try {
      cleanup();
    } catch (Exception e) {
      log.warn("arb-hash cleanup failed: {}", e.getMessage(), e);
    }
  }

  private void cleanup() {
    final long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
    int before = sentAtMs.size();

    sentAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);

    int after = sentAtMs.size();
    if (after != before) {
      log.info("arb-hash cleanup: {} -> {}", before, after);
    }
  }

  @PreDestroy
  public void shutdown() {
    cleaner.shutdownNow();
  }
}
