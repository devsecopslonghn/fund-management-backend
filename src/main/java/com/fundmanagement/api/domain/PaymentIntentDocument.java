package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("payment_intents")
public class PaymentIntentDocument {
  @Id public String id;
  @Indexed public String memberId;
  public List<String> duePeriodIds = new ArrayList<>();
  public long totalAmount;
  public String transferContent;
  /** Immutable payment-plan snapshot metadata. */
  public String selectionMode;
  public Integer requestedMonthCount;
  public String throughMonth;
  public Integer referenceYear;
  @Indexed public Instant createdAt;
  public Instant expiresAt;
  public IntentStatus status;
}
