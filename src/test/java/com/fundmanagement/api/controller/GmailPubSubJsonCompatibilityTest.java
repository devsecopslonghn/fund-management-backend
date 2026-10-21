package com.fundmanagement.api.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.config.SecurityConfig;
import com.fundmanagement.api.service.GmailPushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A real HTTP binding regression: the established Pub/Sub contract uses Jackson 2 JsonNode. */
@WebMvcTest(value = GmailPubSubController.class, properties = {
    "app.security.admin-username=test-admin", "app.security.admin-password=test-only-password",
    "app.gmail.event-enabled=true"})
@Import(SecurityConfig.class)
class GmailPubSubJsonCompatibilityTest {
  @Autowired MockMvc mvc;
  @MockitoBean GmailPushService pushes;
  @MockitoBean GmailProperties properties;
  private static final String PATH = "/api/v1/integrations/gmail/pubsub";
  private static final String ENVELOPE = """
      {"message":{"data":"c3ludGhldGlj","messageId":"synthetic-message"}}
      """;

  @Test void authenticatedEnvelopeBindsToTheExistingJsonNodeContract() throws Exception {
    when(properties.getPubsubSharedToken()).thenReturn("synthetic-push-token");
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .header("X-Gmail-PubSub-Token", "synthetic-push-token").content(ENVELOPE))
        .andExpect(status().isNoContent());
    verify(pushes).process(argThat(node -> node.path("message").path("messageId").asText()
        .equals("synthetic-message")));
  }

  @Test void unauthenticatedEnvelopeNeverReachesIngestion() throws Exception {
    when(properties.getPubsubSharedToken()).thenReturn("synthetic-push-token");
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(ENVELOPE))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(pushes);
  }

  @Test void malformedEnvelopeNeverReachesIngestion() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .header("X-Gmail-PubSub-Token", "synthetic-push-token").content("{invalid"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(pushes);
  }
}
