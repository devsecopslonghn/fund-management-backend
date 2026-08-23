package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailOAuthStateDocument;
import com.fundmanagement.api.repository.GmailCredentialRepository;
import com.fundmanagement.api.repository.GmailOAuthStateRepository;
import java.util.Base64;
import java.util.Optional;
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
    assertThat(response.configured()).isTrue(); assertThat(response.connected()).isFalse(); assertThat(response.toString()).doesNotContain(properties.getClientSecret(), properties.getTokenEncryptionKey());
  }
  private GmailProperties configured() { GmailProperties p = new GmailProperties(); p.setEnabled(true); p.setClientId("client-id"); p.setClientSecret("client-secret-fixture"); p.setRedirectUri("https://frontend.example.test/oauth/callback"); p.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32])); p.setMonitoredMailbox("fund@example.test"); p.setSenderFilter("info@sacombank.com.vn"); return p; }
}
