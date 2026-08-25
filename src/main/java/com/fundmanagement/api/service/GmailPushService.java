package com.fundmanagement.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailPushEventDocument;
import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.repository.GmailCredentialRepository;
import com.fundmanagement.api.repository.GmailPushEventRepository;
import com.fundmanagement.api.repository.GmailWatchStateRepository;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Gmail push adapter. Polling remains the recovery path for delayed/dropped notifications. */
@Service
@ConditionalOnProperties({
    @ConditionalOnProperty(prefix = "app.gmail", name = "enabled", havingValue = "true"),
    @ConditionalOnProperty(prefix = "app.gmail", name = "event-enabled", havingValue = "true")
})
public class GmailPushService {
  public static final String WATCH_ID = "singleton";
  private static final Logger log = LoggerFactory.getLogger(GmailPushService.class);
  private final GmailProperties properties;
  private final GmailMailboxIngestionPort mailbox;
  private final GmailOAuthService oauth;
  private final GmailWatchStateRepository watches;
  private final GmailPushEventRepository events;
  private final GmailCredentialRepository credentials;
  private final IngestionService ingestion;
  private final ObjectMapper mapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public GmailPushService(GmailProperties properties, GmailMailboxIngestionPort mailbox, GmailOAuthService oauth,
      GmailWatchStateRepository watches, GmailPushEventRepository events, GmailCredentialRepository credentials,
      IngestionService ingestion, ObjectMapper mapper) {
    this.properties = properties; this.mailbox = mailbox; this.oauth = oauth; this.watches = watches;
    this.events = events; this.credentials = credentials; this.ingestion = ingestion; this.mapper = mapper;
  }

  public GmailWatchStateDocument registerWatch() {
    if (properties.getPubsubTopicName() == null || properties.getPubsubTopicName().isBlank()) {
      throw new IllegalStateException("Gmail Pub/Sub topic is not configured");
    }
    String mailboxName = properties.getMonitoredMailbox();
    String encodedMailbox = URLEncoder.encode(mailboxName, StandardCharsets.UTF_8);
    String body;
    try {
      body = mapper.writeValueAsString(Map.of("topicName", properties.getPubsubTopicName()));
      HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiBaseUrl() + "/users/" + encodedMailbox + "/watch"))
          .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + oauth.accessToken())
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new IllegalStateException("Gmail watch registration failed");
      JsonNode result = mapper.readTree(response.body());
      String historyId = result.path("historyId").asText("");
      if (historyId.isBlank()) throw new IllegalStateException("Gmail watch response did not contain historyId");
      GmailWatchStateDocument state = watches.findById(WATCH_ID).orElseGet(GmailWatchStateDocument::new);
      state.id = WATCH_ID; state.mailbox = mailboxName; state.topicName = properties.getPubsubTopicName();
      state.historyId = historyId; state.expirationAt = expiration(result.path("expiration").asText(""));
      state.registeredAt = Instant.now(); watches.save(state);
      log.info("Gmail Pub/Sub watch registered; expiration={}", state.expirationAt);
      return state;
    } catch (IOException ex) {
      throw new IllegalStateException("Gmail watch registration failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt(); throw new IllegalStateException("Gmail watch registration interrupted", ex);
    }
  }

  /** Renew before expiry. Gmail watches expire in at most seven days. */
  @Scheduled(fixedDelayString = "${FUND_GMAIL_WATCH_RENEWAL_TICK_MS:3600000}", initialDelayString = "${FUND_GMAIL_WATCH_RENEWAL_INITIAL_DELAY_MS:30000}")
  public void renewIfDue() {
    try {
      Optional<GmailWatchStateDocument> current = watches.findById(WATCH_ID);
      Instant threshold = Instant.now().plus(Duration.ofHours(Math.max(1, properties.getWatchRenewalHours())));
      if (current.isEmpty() || current.get().expirationAt == null || current.get().expirationAt.isBefore(threshold)) registerWatch();
    } catch (RuntimeException ex) {
      log.warn("Gmail Pub/Sub watch renewal failed: {}", safeMessage(ex));
    }
  }

  /** Processes a Pub/Sub push envelope. Replayed message ids are acknowledged safely. */
  public void process(JsonNode envelope) {
    JsonNode message = envelope == null ? null : envelope.path("message");
    String messageId = message == null ? "" : message.path("messageId").asText("");
    String encodedData = message == null ? "" : message.path("data").asText("");
    if (messageId.isBlank() || encodedData.isBlank()) throw new IllegalArgumentException("Gmail Pub/Sub message is invalid");
    JsonNode notification = decode(encodedData);
    String historyId = notification.path("historyId").asText("");
    String emailAddress = notification.path("emailAddress").asText("");
    if (historyId.isBlank() || emailAddress.isBlank()) throw new IllegalArgumentException("Gmail notification is invalid");
    verifyMailbox(emailAddress);
    GmailPushEventDocument previous = events.findById(messageId).orElse(null);
    if (previous != null && "COMPLETED".equals(previous.status)) return;
    GmailPushEventDocument event = previous == null ? new GmailPushEventDocument() : previous;
    event.id = messageId; event.historyId = historyId; event.status = "PROCESSING"; event.receivedAt = Instant.now(); event.failureReason = null;
    try { events.save(event); } catch (DuplicateKeyException duplicate) { /* Another replica will perform the same idempotent ingestion. */ }
    try {
      GmailWatchStateDocument state = watches.findById(WATCH_ID).orElseThrow(() -> new IllegalStateException("Gmail watch cursor is not initialized"));
      if (compare(historyId, state.historyId) > 0) {
        List<MailboxIngestionPort.MailboxMessage> messages = mailbox.fetchMessagesSinceHistoryId(state.historyId);
        for (MailboxIngestionPort.MailboxMessage candidate : messages) {
          ingestion.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest(candidate.providerMessageId(), candidate.rawPayload()));
        }
        if (compare(historyId, state.historyId) > 0) state.historyId = historyId;
        state.lastNotificationAt = Instant.now(); watches.save(state);
      }
      event.status = "COMPLETED"; event.completedAt = Instant.now(); events.save(event);
    } catch (RuntimeException failure) {
      event.status = "FAILED"; event.failureReason = safeMessage(failure); events.save(event); throw failure;
    }
  }

  private void verifyMailbox(String emailAddress) {
    String configured = credentials.findById("singleton").map(c -> c.mailbox).orElse(properties.getMonitoredMailbox());
    if (configured != null && !configured.equalsIgnoreCase("me") && !configured.equalsIgnoreCase(emailAddress)) {
      throw new IllegalArgumentException("Gmail notification mailbox is not configured");
    }
  }

  private JsonNode decode(String encoded) {
    try {
      byte[] bytes;
      try { bytes = Base64.getUrlDecoder().decode(encoded); } catch (IllegalArgumentException ex) { bytes = Base64.getDecoder().decode(encoded); }
      return mapper.readTree(bytes);
    } catch (IOException | IllegalArgumentException ex) { throw new IllegalArgumentException("Gmail Pub/Sub data is invalid", ex); }
  }

  private Instant expiration(String millis) {
    try { return Instant.ofEpochMilli(Long.parseLong(millis)); } catch (RuntimeException ex) { return Instant.now().plus(Duration.ofDays(7)); }
  }

  private int compare(String left, String right) {
    try { return new BigInteger(left == null ? "0" : left).compareTo(new BigInteger(right == null ? "0" : right)); }
    catch (NumberFormatException ex) { return String.valueOf(left).compareTo(String.valueOf(right)); }
  }

  private String safeMessage(Throwable failure) {
    String value = failure == null ? "unknown" : failure.getMessage();
    if (value == null || value.isBlank()) return "processing_failed";
    return value.length() > 200 ? value.substring(0, 200) : value;
  }
}
