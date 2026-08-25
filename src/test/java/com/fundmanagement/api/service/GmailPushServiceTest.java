package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailCredentialDocument;
import com.fundmanagement.api.domain.GmailPushEventDocument;
import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.repository.GmailCredentialRepository;
import com.fundmanagement.api.repository.GmailPushEventRepository;
import com.fundmanagement.api.repository.GmailWatchStateRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailPushServiceTest {
  @Mock GmailMailboxIngestionPort mailbox;
  @Mock GmailOAuthService oauth;
  @Mock GmailWatchStateRepository watches;
  @Mock GmailPushEventRepository events;
  @Mock GmailCredentialRepository credentials;
  @Mock IngestionService ingestion;

  @Test void notificationUsesHistoryCursorAndDuplicateDeliveryIsIdempotent() throws Exception {
    GmailProperties properties = properties();
    GmailWatchStateDocument state = new GmailWatchStateDocument(); state.id = "singleton"; state.historyId = "100"; state.mailbox = "fund@example.test";
    GmailCredentialDocument credential = new GmailCredentialDocument(); credential.id = "singleton"; credential.mailbox = "fund@example.test";
    when(watches.findById("singleton")).thenReturn(Optional.of(state));
    when(credentials.findById("singleton")).thenReturn(Optional.of(credential));
    when(events.findById("pubsub-1")).thenReturn(Optional.empty(), Optional.of(completed("pubsub-1")));
    when(mailbox.fetchMessagesSinceHistoryId("100")).thenReturn(java.util.List.of(new MailboxIngestionPort.MailboxMessage("gmail-1", "raw")));
    doAnswer(invocation -> { ((GmailPushEventDocument) invocation.getArgument(0)).status = "COMPLETED"; return invocation.getArgument(0); }).when(events).save(any());
    GmailPushService service = new GmailPushService(properties, mailbox, oauth, watches, events, credentials, ingestion, new ObjectMapper());
    JsonNode envelope = new ObjectMapper().readTree(envelope("pubsub-1", "fund@example.test", "101"));
    service.process(envelope); service.process(envelope);
    verify(mailbox, times(1)).fetchMessagesSinceHistoryId("100");
    verify(ingestion, times(1)).ingest(any());
    assertThat(state.historyId).isEqualTo("101");
  }

  private GmailPushEventDocument completed(String id) { GmailPushEventDocument event = new GmailPushEventDocument(); event.id = id; event.status = "COMPLETED"; return event; }
  private GmailProperties properties() { GmailProperties properties = new GmailProperties(); properties.setEnabled(true); properties.setEventEnabled(true); properties.setMonitoredMailbox("fund@example.test"); properties.setPubsubTopicName("projects/test/topics/gmail"); return properties; }
  private String envelope(String id, String email, String historyId) {
    String data = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"emailAddress\":\"" + email + "\",\"historyId\":\"" + historyId + "\"}").getBytes(StandardCharsets.UTF_8));
    return "{\"message\":{\"messageId\":\"" + id + "\",\"data\":\"" + data + "\"},\"subscription\":\"sub\"}";
  }
}
