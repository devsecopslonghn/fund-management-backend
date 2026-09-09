package com.fundmanagement.api.service;

import java.time.Instant;
import org.springframework.stereotype.Service;

/** In-memory operational telemetry for the Gmail ingestion path. */
@Service
public class GmailIngestionHealthService {
  private volatile Instant lastPollAt, lastPollSuccessAt, lastPollFailureAt;
  private volatile String lastPollError;
  private volatile int lastPollCandidates, lastPollMatched, lastPollRejected;
  private volatile Instant lastPushAt, lastPushSuccessAt, lastPushFailureAt;
  private volatile String lastPushError;
  public void pollStarted() { lastPollAt = Instant.now(); }
  public void pollSucceeded(int candidates, int matched, int rejected) { lastPollSuccessAt = Instant.now(); lastPollError = null; lastPollCandidates = candidates; lastPollMatched = matched; lastPollRejected = rejected; }
  public void pollFailed(Throwable failure) { lastPollFailureAt = Instant.now(); lastPollError = safeMessage(failure); }
  public void pushReceived() { lastPushAt = Instant.now(); }
  public void pushSucceeded() { lastPushSuccessAt = Instant.now(); lastPushError = null; }
  public void pushFailed(Throwable failure) { lastPushFailureAt = Instant.now(); lastPushError = safeMessage(failure); }
  public Snapshot snapshot() { return new Snapshot(lastPollAt, lastPollSuccessAt, lastPollFailureAt, lastPollError, lastPollCandidates, lastPollMatched, lastPollRejected, lastPushAt, lastPushSuccessAt, lastPushFailureAt, lastPushError); }
  private String safeMessage(Throwable failure) { String message = failure == null ? "unknown" : failure.getMessage(); if (message == null || message.isBlank()) return failure == null ? "unknown" : failure.getClass().getSimpleName(); return message.length() > 200 ? message.substring(0, 200) : message; }
  public record Snapshot(Instant lastPollAt, Instant lastPollSuccessAt, Instant lastPollFailureAt, String lastPollError, int lastPollCandidates, int lastPollMatched, int lastPollRejected, Instant lastPushAt, Instant lastPushSuccessAt, Instant lastPushFailureAt, String lastPushError) {}
}
