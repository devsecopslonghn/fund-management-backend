package com.fundmanagement.api.service;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in polling fallback; duplicate Gmail deliveries are absorbed by providerMessageId. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "polling-enabled", havingValue = "true")
public class GmailPollingWorker {
  private static final Logger log = LoggerFactory.getLogger(GmailPollingWorker.class);
  private final MailboxIngestionPort mailbox; private final IngestionService ingestion; private final GmailPollingLeaseService leaseService; private final PaymentDetectionWindowService detectionWindow; private final SettingsService settings; private final TaskExecutor taskExecutor; private final AtomicLong lastBatchPollAt = new AtomicLong(0);
  /** Compatibility constructor for focused tests and deployments that do not use fast polling. */
  public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion, GmailPollingLeaseService leaseService) { this(mailbox, ingestion, leaseService, null, null, null); }
  public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion, GmailPollingLeaseService leaseService, PaymentDetectionWindowService detectionWindow) { this(mailbox, ingestion, leaseService, detectionWindow, null, null); }
  @Autowired public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion, GmailPollingLeaseService leaseService, PaymentDetectionWindowService detectionWindow, SettingsService settings, @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) { this.mailbox = mailbox; this.ingestion = ingestion; this.leaseService = leaseService; this.detectionWindow = detectionWindow; this.settings = settings; this.taskExecutor = taskExecutor; }
  /** Full mailbox reconciliation. The ten-second scheduler tick only checks the persisted interval; it does not call Gmail unless due. */
  @Scheduled(fixedDelayString = "${app.gmail.scheduler-tick-ms:10000}", initialDelayString = "${FUND_GMAIL_POLL_INITIAL_DELAY_MS:30000}")
  public void poll() {
    if (detectionWindow != null && safeWindowActive()) return;
    long now = System.currentTimeMillis();
    long interval = configuredBatchIntervalMs();
    long previous = lastBatchPollAt.get();
    if (previous > 0 && now - previous < interval) return;
    if (!lastBatchPollAt.compareAndSet(previous, now)) return;
    runPoll();
  }
  /** Five-second schedule is cheap while idle; Gmail is only queried during an active window. */
  @Scheduled(fixedDelayString = "${FUND_GMAIL_FAST_POLL_INTERVAL_MS:5000}", initialDelayString = "${FUND_GMAIL_FAST_POLL_INITIAL_DELAY_MS:5000}")
  public void fastPoll() {
    if (detectionWindow == null || !safeWindowActive()) return;
    runPoll();
  }
  /** Queued after a payment intent is created so a just-arrived alert is not delayed five seconds. */
  public void pollNow() { runPoll(); }
  /** A successful OAuth reconnect must not wait for the previous failed batch interval. */
  @EventListener
  public void onGmailConnected(GmailOAuthConnectedEvent event) {
    lastBatchPollAt.set(0);
    scheduleImmediatePoll();
  }
  /** A newly started replica should reconcile once before waiting for the batch interval. */
  @EventListener
  public void onApplicationReady(ApplicationReadyEvent event) {
    log.info("Gmail application-ready poll triggered");
    scheduleImmediatePoll();
  }
  private void scheduleImmediatePoll() {
    Runnable poll = this::pollNow;
    try { if (taskExecutor == null) poll.run(); else taskExecutor.execute(poll); }
    catch (RuntimeException failure) { log.warn("Immediate Gmail poll could not be scheduled: {}", failure.getMessage()); }
  }
  private void runPoll() {
    Optional<GmailPollingLeaseService.LeaseHandle> acquired;
    try { acquired = leaseService.tryAcquire(); } catch (RuntimeException ignored) { return; }
    if (acquired.isEmpty()) { log.debug("Gmail poll skipped because another replica owns the lease"); return; }
    try {
      List<MailboxIngestionPort.MailboxMessage> messages = mailbox.fetchSacombankMessages();
      log.info("Gmail poll fetched {} candidate bank alert(s)", messages.size());
      for (MailboxIngestionPort.MailboxMessage message : messages) {
        try { ingestion.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest(message.providerMessageId(), message.rawPayload())); }
        catch (RuntimeException failure) {
          if ("Bank message was not for the configured receiving account".equals(failure.getMessage())) {
            String payload = message.rawPayload() == null ? "" : message.rawPayload();
            String configured = settings == null ? "" : settings.get().accountNumber();
            String digits = configured == null ? "" : configured.replaceAll("\\D", "");
            log.warn("Gmail bank alert account rejected payloadLength={} hasConfiguredAccount={} hasAccountLabel={} hasTableMarker={} hasHtmlEntity={}",
                payload.length(), !digits.isBlank() && payload.contains(digits),
                payload.toLowerCase(java.util.Locale.ROOT).contains("account") || payload.toLowerCase(java.util.Locale.ROOT).contains("tài khoản"),
                payload.contains("|"), payload.contains("&#"));
          }
          log.warn("Gmail bank alert ingestion failed: {}", failure.getMessage());
        }
      }
    } catch (RuntimeException failure) { log.warn("Gmail poll failed: {}", failure.getMessage()); }
    finally { try { leaseService.release(acquired.get()); } catch (RuntimeException ignored) { /* TTL expiry remains the fallback. */ } }
  }
  private boolean safeWindowActive() { try { return detectionWindow.isActive(); } catch (RuntimeException ignored) { return false; } }
  private long configuredBatchIntervalMs() {
    try {
      if (settings != null) {
        var current = settings.get();
        if (current != null) return Math.max(60_000L, Math.min(3_600_000L, current.gmailBatchPollIntervalSeconds() * 1_000L));
      }
    } catch (RuntimeException ignored) { /* Fall back to the safe default if settings are unavailable. */ }
    return 60_000L;
  }
}
