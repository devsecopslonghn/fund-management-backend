package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Singleton marker used to activate short-lived payment detection polling. */
@Document("payment_detection_windows")
public class PaymentDetectionWindowDocument {
  @Id public String id;
  @Indexed public Instant activeUntil;
  public Instant updatedAt;
}
