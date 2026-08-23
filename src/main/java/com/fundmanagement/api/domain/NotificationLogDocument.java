package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("notification_logs")
public class NotificationLogDocument {
  @Id public String id;
  @Indexed public String idempotencyKey;
  public String memberId;
  public String type;
  public String channel;
  public String status;
  public String errorCode;
  public Instant sentAt;
}
