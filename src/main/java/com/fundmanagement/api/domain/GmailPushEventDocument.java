package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Inbox marker for Pub/Sub delivery ids; retained for duplicate delivery safety. */
@Document("gmail_push_events")
public class GmailPushEventDocument {
  @Id public String id;
  public String historyId;
  public String status;
  public Instant receivedAt;
  public Instant completedAt;
  public String failureReason;
}
