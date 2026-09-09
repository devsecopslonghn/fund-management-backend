package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("gmail_ingestion_health")
public class GmailIngestionHealthDocument {
  @Id public String id;
  public Instant lastPollAt, lastPollSuccessAt, lastPollFailureAt, lastPushAt, lastPushSuccessAt, lastPushFailureAt;
  public String lastPollError, lastPushError;
  public int lastPollCandidates, lastPollMatched, lastPollRejected;
}
