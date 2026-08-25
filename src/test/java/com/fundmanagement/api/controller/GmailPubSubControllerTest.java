package com.fundmanagement.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.service.GmailPushService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GmailPubSubControllerTest {
  @Test void rejectsMissingOrWrongPushTokenWithoutProcessing() {
    GmailProperties properties = new GmailProperties(); properties.setPubsubSharedToken("test-secret");
    GmailPushService service = mock(GmailPushService.class);
    GmailPubSubController controller = new GmailPubSubController(service, properties);
    var response = controller.push(new ObjectMapper().createObjectNode(), null, null, "wrong");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(service, never()).process(org.mockito.ArgumentMatchers.any());
  }
}
