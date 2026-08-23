package com.fundmanagement.api.service;

import com.fundmanagement.api.config.GmailProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in polling fallback; duplicate Gmail deliveries are absorbed by providerMessageId. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "polling-enabled", havingValue = "true")
public class GmailPollingWorker {
  private final MailboxIngestionPort mailbox; private final IngestionService ingestion;
  public GmailPollingWorker(MailboxIngestionPort mailbox, IngestionService ingestion) { this.mailbox = mailbox; this.ingestion = ingestion; }
  @Scheduled(fixedDelayString = "${FUND_GMAIL_POLL_INTERVAL_MS:180000}", initialDelayString = "${FUND_GMAIL_POLL_INITIAL_DELAY_MS:30000}")
  public void poll() { for (MailboxIngestionPort.MailboxMessage message : mailbox.fetchUnreadSacombankMessages()) ingestion.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest(message.providerMessageId(), message.rawPayload())); }
}
