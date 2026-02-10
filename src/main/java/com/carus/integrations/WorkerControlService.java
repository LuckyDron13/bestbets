package com.carus.integrations;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class WorkerControlService {
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private final AtomicBoolean restart = new AtomicBoolean(false);

  public void pause() { paused.set(true); }
  public void resume() { paused.set(false); }
  public void restart() { restart.set(true); paused.set(false); }

  public boolean isPaused() { return paused.get(); }
  public boolean consumeRestart() { return restart.getAndSet(false); }
}
