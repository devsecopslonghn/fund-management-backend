package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("bank_transactions")
public class BankTransactionDocument {
  @Id public String id;
  @Indexed(unique = true) public String providerMessageId;
  @Indexed public Instant occurredAt;
  public String accountNumber;
  public long amount;
  public String direction;
  /** Parsed/normalized content only. Raw payload is retained in a restricted store field. */
  public String parsedContent;
  public String rawPayload;
  public Long availableBalance;
  @Indexed public String reconciliationStatus;
  public List<MatchCandidate> matchCandidates = new ArrayList<>();
  /** Safe reconciliation metadata for the Admin review queue. */
  public String matchMethod;
  public String conflictReason;
  public String matchedMemberId;
  public List<String> matchedDuePeriodIds = new ArrayList<>();
  public String reviewedBy;
  public Instant reviewedAt;
  public Instant createdAt;

  public static class MatchCandidate {
    public String memberId;
    public double confidence;
    public List<String> signals = new ArrayList<>();
  }
}
