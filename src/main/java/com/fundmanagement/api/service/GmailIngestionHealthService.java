package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.GmailIngestionHealthDocument;
import com.fundmanagement.api.repository.GmailIngestionHealthRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** In-memory operational telemetry for the Gmail ingestion path. */
@Service
public class GmailIngestionHealthService {
  private static final String ID = "singleton";
  private final GmailIngestionHealthRepository repository;
  public GmailIngestionHealthService(GmailIngestionHealthRepository repository) { this.repository = repository; }
  public void pollStarted() { update(value -> value.lastPollAt = Instant.now()); }
  public void pollSucceeded(int candidates, int matched, int rejected) { update(value -> { value.lastPollSuccessAt = Instant.now(); value.lastPollError = null; value.lastPollCandidates = candidates; value.lastPollMatched = matched; value.lastPollRejected = rejected; }); }
  public void pollFailed(Throwable failure) { update(value -> { value.lastPollFailureAt = Instant.now(); value.lastPollError = safeMessage(failure); }); }
  public void pushReceived() { update(value -> value.lastPushAt = Instant.now()); }
  public void pushSucceeded() { update(value -> { value.lastPushSuccessAt = Instant.now(); value.lastPushError = null; }); }
  public void pushFailed(Throwable failure) { update(value -> { value.lastPushFailureAt = Instant.now(); value.lastPushError = safeMessage(failure); }); }
  public Snapshot snapshot() { GmailIngestionHealthDocument value = repository.findById(ID).orElse(new GmailIngestionHealthDocument()); return new Snapshot(value.lastPollAt, value.lastPollSuccessAt, value.lastPollFailureAt, value.lastPollError, value.lastPollCandidates, value.lastPollMatched, value.lastPollRejected, value.lastPushAt, value.lastPushSuccessAt, value.lastPushFailureAt, value.lastPushError); }
  private void update(java.util.function.Consumer<GmailIngestionHealthDocument> change) { GmailIngestionHealthDocument value = repository.findById(ID).orElseGet(() -> { var created = new GmailIngestionHealthDocument(); created.id = ID; return created; }); change.accept(value); repository.save(value); }
  private String safeMessage(Throwable failure) { String message = failure == null ? "unknown" : failure.getMessage(); if (message == null || message.isBlank()) return failure == null ? "unknown" : failure.getClass().getSimpleName(); return message.length() > 200 ? message.substring(0, 200) : message; }
  public record Snapshot(Instant lastPollAt, Instant lastPollSuccessAt, Instant lastPollFailureAt, String lastPollError, int lastPollCandidates, int lastPollMatched, int lastPollRejected, Instant lastPushAt, Instant lastPushSuccessAt, Instant lastPushFailureAt, String lastPushError) {}
}
