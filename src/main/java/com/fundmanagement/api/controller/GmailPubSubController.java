package com.fundmanagement.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailWatchStateDocument;
import com.fundmanagement.api.service.GmailPushService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public Pub/Sub push endpoint. It is authenticated by the configured token; no Gmail data is returned. */
@RestController
@RequestMapping("/api/v1/integrations/gmail/pubsub")
@ConditionalOnProperty(prefix = "app.gmail", name = "event-enabled", havingValue = "true")
public class GmailPubSubController {
  private final GmailPushService service;
  private final GmailProperties properties;
  public GmailPubSubController(GmailPushService service, GmailProperties properties) { this.service = service; this.properties = properties; }

  @PostMapping
  public ResponseEntity<Void> push(@RequestBody JsonNode envelope,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Gmail-PubSub-Token", required = false) String headerToken,
      @RequestParam(value = "token", required = false) String queryToken) {
    if (!authorized(authorization, headerToken, queryToken)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    service.process(envelope);
    return ResponseEntity.noContent().build();
  }

  private boolean authorized(String authorization, String headerToken, String queryToken) {
    String expected = properties.getPubsubSharedToken();
    if (expected == null || expected.isBlank()) return false;
    String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
    String actual = firstNonBlank(queryToken, firstNonBlank(headerToken, bearer));
    return !actual.isBlank() && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
  private String firstNonBlank(String first, String fallback) { return first != null && !first.isBlank() ? first : (fallback == null ? "" : fallback); }
}
