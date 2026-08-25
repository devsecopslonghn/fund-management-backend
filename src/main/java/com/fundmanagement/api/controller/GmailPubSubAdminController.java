package com.fundmanagement.api.controller;

import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.service.GmailPushService;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super Admin operation used after OAuth connection and during controlled event-path testing. */
@RestController
@RequestMapping("/api/v1/admin/gmail/pubsub")
@ConditionalOnProperty(prefix = "app.gmail", name = "event-enabled", havingValue = "true")
public class GmailPubSubAdminController {
  private final GmailPushService service;
  public GmailPubSubAdminController(GmailPushService service) { this.service = service; }
  @PostMapping("/watch") public GmailWatchResponse register(Principal principal) {
    GmailWatchStateDocument state = service.registerWatch();
    return new GmailWatchResponse(state.topicName, state.historyId, state.expirationAt);
  }
  public record GmailWatchResponse(String topicName, String historyId, java.time.Instant expirationAt) {}
}
