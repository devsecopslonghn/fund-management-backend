package com.fundmanagement.api.service;

import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scheduler; Mongo's unique member/month index is the final idempotency boundary. */
@Component
public class DuesScheduler {
  private final DuesService dues; private final SettingsService settings;
  private final AtomicBoolean running = new AtomicBoolean();
  public DuesScheduler(DuesService dues, SettingsService settings) { this.dues = dues; this.settings = settings; }

  @Scheduled(cron = "${FUND_DUE_SCHEDULER_CRON:0 5 0 1 * *}", zone = "${FUND_DUE_SCHEDULER_ZONE:Asia/Ho_Chi_Minh}")
  public void generateCurrentMonth() {
    if (!settings.get().dueSchedulerEnabled()) return;
    if (!running.compareAndSet(false, true)) return;
    try { dues.generate(YearMonth.now().toString()); } finally { running.set(false); }
  }
}
