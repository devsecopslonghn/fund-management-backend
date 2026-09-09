package com.fundmanagement.api.controller;

import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.service.GmailPushService;
import com.fundmanagement.api.service.GmailMailboxIngestionPort;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super Admin operation used after OAuth connection and during controlled event-path testing. */
@RestController
@RequestMapping("/api/v1/admin/gmail/pubsub")
@ConditionalOnProperty(prefix = "app.gmail", name = "event-enabled", havingValue = "true")
public class GmailPubSubAdminController {
  private final GmailPushService service; private final GmailMailboxIngestionPort mailbox;
  public GmailPubSubAdminController(GmailPushService service, GmailMailboxIngestionPort mailbox) { this.service = service; this.mailbox = mailbox; }
  @PostMapping("/watch") public GmailWatchResponse register(Principal principal) {
    GmailWatchStateDocument state = service.registerWatch();
    return new GmailWatchResponse(state.topicName, state.historyId, state.expirationAt);
  }
  @GetMapping("/diagnostic-messages")
  public java.util.List<GmailMailboxIngestionPort.GmailDiagnosticMessage> diagnosticMessages(
      @RequestParam(required = false) String messageId, @RequestParam(required = false) String threadId) {
    return mailbox.diagnosticMessages(messageId, threadId);
  }
  @GetMapping("/diagnostic-recent-metadata")
  public java.util.List<GmailMailboxIngestionPort.GmailRecentMessageMetadata> recentMetadata() {
    return mailbox.recentMessageMetadata();
  }
  public record GmailWatchResponse(String topicName, String historyId, java.time.Instant expirationAt) {}
}
