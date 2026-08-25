package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Gmail users.watch cursor. The history id is the durable event checkpoint. */
@Document("gmail_watch_state")
public class GmailWatchStateDocument {
  @Id public String id;
  public String mailbox;
  public String topicName;
  public String historyId;
  public Instant expirationAt;
  public Instant registeredAt;
  public Instant lastNotificationAt;
}
