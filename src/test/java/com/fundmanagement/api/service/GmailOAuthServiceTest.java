package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailOAuthStateDocument;
import com.fundmanagement.api.repository.GmailCredentialRepository;
import com.fundmanagement.api.repository.GmailOAuthStateRepository;
import java.util.Base64;
import java.util.Optional;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class GmailOAuthServiceTest {
  @Mock GmailOAuthStateRepository states; @Mock GmailCredentialRepository credentials; @Mock MongoTemplate mongo; @Mock AuditService audit;
  @Test void startUsesReadonlyScopePkceAndExpiryWithoutNetworkCall() {
    GmailProperties properties = configured(); when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var response = new GmailOAuthService(properties, new GmailTokenCrypto(), states, credentials, mongo, audit, new ObjectMapper()).start("super-admin");
    assertThat(response.authorizationUrl()).contains("gmail.readonly", "code_challenge=", "code_challenge_method=S256", "access_type=offline", "prompt=consent", "state="); assertThat(response.expiresAt()).isAfter(java.time.Instant.now());
  }
  @Test void statusDoesNotExposeCredentialMaterial() {
    GmailProperties properties = configured(); when(credentials.findById("singleton")).thenReturn(Optional.empty());
    var response = new GmailOAuthService(properties, new GmailTokenCrypto(), states, credentials, mongo, audit, new ObjectMapper()).status();
    assertThat(response.configured()).isTrue(); assertThat(response.connected()).isFalse(); assertThat(response.refreshStatus()).isEqualTo("NOT_CONNECTED"); assertThat(response.toString()).doesNotContain(properties.getClientSecret(), properties.getTokenEncryptionKey());
  }
  @Test void statusShowsPersistedRefreshFailureWithoutExposingSecrets() {
    GmailProperties properties = configured();
    var credential = new com.fundmanagement.api.domain.GmailCredentialDocument(); credential.id = "singleton"; credential.connectedAt = java.time.Instant.parse("2026-08-23T19:31:56Z"); credential.lastRefreshFailureAt = java.time.Instant.parse("2026-09-03T04:00:00Z"); credential.lastRefreshErrorCode = "invalid_grant";
    when(credentials.findById("singleton")).thenReturn(Optional.of(credential));
    var response = new GmailOAuthService(properties, new GmailTokenCrypto(), states, credentials, mongo, audit, new ObjectMapper()).status();
    assertThat(response.connected()).isTrue(); assertThat(response.refreshStatus()).isEqualTo("FAILED"); assertThat(response.lastRefreshErrorCode()).isEqualTo("invalid_grant"); assertThat(response.toString()).doesNotContain(properties.getClientSecret(), properties.getTokenEncryptionKey());
  }
  @Test void refreshFailurePersistsSafeProviderErrorCode() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/token", exchange -> {
      byte[] body = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(400, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try {
      GmailProperties properties = configured(); properties.setTokenEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      GmailTokenCrypto crypto = new GmailTokenCrypto();
      var credential = new com.fundmanagement.api.domain.GmailCredentialDocument(); credential.id = "singleton"; credential.encryptedRefreshToken = crypto.encrypt("refresh-token", properties.getTokenEncryptionKey());
      when(credentials.findById("singleton")).thenReturn(Optional.of(credential));
      var service = new GmailOAuthService(properties, crypto, states, credentials, mongo, audit, new ObjectMapper());

      assertThatThrownBy(service::accessToken).hasMessage("Google OAuth request failed: invalid_grant");
      assertThat(credential.lastRefreshErrorCode).isEqualTo("invalid_grant");
      assertThat(credential.lastRefreshFailureAt).isNotNull();
    } finally {
      server.stop(0);
    }
  }
  private GmailProperties configured() { GmailProperties p = new GmailProperties(); p.setEnabled(true); p.setClientId("client-id"); p.setClientSecret("client-secret-fixture"); p.setRedirectUri("https://frontend.example.test/oauth/callback"); p.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32])); p.setMonitoredMailbox("fund@example.test"); p.setSenderFilter("info@sacombank.com.vn"); return p; }
}
