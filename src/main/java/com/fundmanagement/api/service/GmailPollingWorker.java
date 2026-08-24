package com.fundmanagement.api.service;

import com.fundmanagement.api.config.GmailProperties;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in polling fallback; duplicate Gmail deliveries are absorbed by providerMessageId. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "polling-enabled", havingValue = "true")
public class GmailPollingWorker {
  private final MailboxIngestionPort mailbox; private final IngestionService ingestion; private final GmailPollingLeaseService leaseService; private final PaymentDetectionWindowService detectionWindow;
  /** Compatibility constructor for focused tests and deployments that do not use fast polling. */
  public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion, GmailPollingLeaseService leaseService) { this(mailbox, ingestion, leaseService, null); }
  @Autowired public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion, GmailPollingLeaseService leaseService, PaymentDetectionWindowService detectionWindow) { this.mailbox = mailbox; this.ingestion = ingestion; this.leaseService = leaseService; this.detectionWindow = detectionWindow; }
  @Scheduled(fixedDelayString = "${FUND_GMAIL_POLL_INTERVAL_MS:180000}", initialDelayString = "${FUND_GMAIL_POLL_INITIAL_DELAY_MS:30000}")
  public void poll() {
    if (detectionWindow != null && safeWindowActive()) return;
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
  private void runPoll() {
    Optional<GmailPollingLeaseService.LeaseHandle> acquired;
    try { acquired = leaseService.tryAcquire(); } catch (RuntimeException ignored) { return; }
    if (acquired.isEmpty()) return;
    try {
      for (MailboxIngestionPort.MailboxMessage message : mailbox.fetchUnreadSacombankMessages()) {
        try { ingestion.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest(message.providerMessageId(), message.rawPayload())); } catch (RuntimeException ignored) { /* Continue safely; provider IDs make retries idempotent. */ }
      }
    } catch (RuntimeException ignored) { /* Lease is always released; next interval retries. */ }
    finally { try { leaseService.release(acquired.get()); } catch (RuntimeException ignored) { /* TTL expiry remains the fallback. */ } }
  }
  private boolean safeWindowActive() { try { return detectionWindow.isActive(); } catch (RuntimeException ignored) { return false; } }
}
