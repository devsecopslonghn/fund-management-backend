package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("manual_payments")
public class ManualPaymentDocument {
  @Id public String id;
  @Indexed public String memberId;
  @Indexed(unique = true) public String idempotencyKey;
  public long amount;
  public long unallocatedAmount;
  public Instant occurredAt;
  public String note;
  public String recordedBy;
  public List<ManualAllocation> allocations = new ArrayList<>();
  /** Reversals are append-only financial corrections; the original payment remains auditable. */
  public boolean reversed;
  public Instant reversedAt;
  public String reversedBy;
  public String reversalReason;
  public Instant createdAt;
  public static class ManualAllocation {
    public String duePeriodId;
    public long amount;
    public ManualAllocation() {}
    public ManualAllocation(String duePeriodId, long amount) { this.duePeriodId = duePeriodId; this.amount = amount; }
  }
}
