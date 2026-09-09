package com.fundmanagement.api.controller;

import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.GmailWatchStateRepository;
import com.fundmanagement.api.service.GmailIngestionHealthService;
import com.fundmanagement.api.service.GmailOAuthService;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/gmail/health")
public class GmailHealthController {
  private final GmailProperties properties; private final GmailOAuthService oauth;
  private final GmailWatchStateRepository watches; private final GmailIngestionHealthService health;
  public GmailHealthController(GmailProperties properties, GmailOAuthService oauth, GmailWatchStateRepository watches, GmailIngestionHealthService health) { this.properties = properties; this.oauth = oauth; this.watches = watches; this.health = health; }

  @GetMapping
  public ApiDtos.GmailHealthResponse status() {
    var oauthStatus = oauth.status(); var snapshot = health.snapshot();
    Optional<GmailWatchStateDocument> watch = watches.findById("singleton");
    Instant expiration = watch.map(value -> value.expirationAt).orElse(null);
    boolean watchHealthy = !properties.isEventEnabled() || expiration != null && expiration.isAfter(Instant.now());
    long pollIntervalMs = Math.max(60_000L, Math.min(3_600_000L, properties.getBatchPollIntervalMs()));
    Instant pollStaleAfter = Instant.now().minusMillis(Math.max(600_000L, pollIntervalMs * 2));
    boolean pollStale = properties.isPollingEnabled() && (snapshot.lastPollSuccessAt() == null || snapshot.lastPollSuccessAt().isBefore(pollStaleAfter));
    boolean pollFailed = snapshot.lastPollFailureAt() != null && (snapshot.lastPollSuccessAt() == null || snapshot.lastPollFailureAt().isAfter(snapshot.lastPollSuccessAt()));
    boolean pushFailed = snapshot.lastPushFailureAt() != null && (snapshot.lastPushSuccessAt() == null || snapshot.lastPushFailureAt().isAfter(snapshot.lastPushSuccessAt()));
    String status = !oauthStatus.configured() || !oauthStatus.connected() ? "DOWN" : "FAILED".equals(oauthStatus.refreshStatus()) || !watchHealthy || pollStale || pollFailed || pushFailed ? "DEGRADED" : "HEALTHY";
    String pollError = snapshot.lastPollError() != null ? snapshot.lastPollError() : oauthStatus.lastRefreshErrorCode();
    return new ApiDtos.GmailHealthResponse(status, properties.isPollingEnabled(), properties.isEventEnabled(), oauthStatus.refreshStatus(), expiration, watchHealthy, snapshot.lastPollAt(), snapshot.lastPollSuccessAt(), snapshot.lastPollFailureAt(), pollError, snapshot.lastPollCandidates(), snapshot.lastPollMatched(), snapshot.lastPollRejected(), snapshot.lastPushAt(), snapshot.lastPushSuccessAt(), snapshot.lastPushFailureAt(), snapshot.lastPushError());
  }
}
