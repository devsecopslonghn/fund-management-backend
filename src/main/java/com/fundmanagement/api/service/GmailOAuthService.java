package com.fundmanagement.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailCredentialDocument;
import com.fundmanagement.api.domain.GmailOAuthStateDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.GmailCredentialRepository;
import com.fundmanagement.api.repository.GmailOAuthStateRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class GmailOAuthService {
  public static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
  private static final String CREDENTIAL_ID = "singleton";
  private final GmailProperties properties; private final GmailTokenCrypto crypto; private final GmailOAuthStateRepository states; private final GmailCredentialRepository credentials; private final MongoTemplate mongo; private final AuditService audit; private final ObjectMapper mapper; private final HttpClient http; private final SecureRandom random = new SecureRandom();
  public GmailOAuthService(GmailProperties properties, GmailTokenCrypto crypto, GmailOAuthStateRepository states, GmailCredentialRepository credentials, MongoTemplate mongo, AuditService audit, ObjectMapper mapper) { this.properties = properties; this.crypto = crypto; this.states = states; this.credentials = credentials; this.mongo = mongo; this.audit = audit; this.mapper = mapper; this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); }

  public ApiDtos.GmailOAuthStartResponse start(String actor) {
    requireConfigured();
    String state = randomValue(32); String verifier = randomValue(48); Instant expires = Instant.now().plusSeconds(properties.getStateTtlSeconds());
    GmailOAuthStateDocument document = new GmailOAuthStateDocument(); document.id = UUID.randomUUID().toString(); document.stateHash = crypto.hash(state); document.encryptedCodeVerifier = crypto.encrypt(verifier, properties.getTokenEncryptionKey()); document.actor = actor; document.expiresAt = expires; document.createdAt = Instant.now(); document.used = false; states.save(document);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    String url = properties.getAuthorizeEndpoint() + "?" + form(Map.of("client_id", properties.getClientId(), "redirect_uri", properties.getRedirectUri(), "response_type", "code", "scope", READ_ONLY_SCOPE, "access_type", "offline", "prompt", "consent", "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
    return new ApiDtos.GmailOAuthStartResponse(url, expires);
  }

  public ApiDtos.GmailOAuthCallbackResponse callback(String stateValue, String code, String error) {
    GmailOAuthStateDocument state = consumeState(stateValue);
    if (error != null && !error.isBlank()) return new ApiDtos.GmailOAuthCallbackResponse(false, "Google authorization was not completed");
    if (code == null || code.isBlank()) throw new IllegalArgumentException("Authorization code is missing");
    String verifier = crypto.decrypt(state.encryptedCodeVerifier, properties.getTokenEncryptionKey()); Map<String, String> token = exchangeCode(code, verifier);
    String refreshToken = token.get("refresh_token"); if (refreshToken == null || refreshToken.isBlank()) throw new IllegalStateException("Google did not return a refresh token; reconnect with consent");
    String accessToken = token.get("access_token"); if (accessToken == null || accessToken.isBlank()) throw new IllegalStateException("Google did not return an access token");
    String mailbox = verifyMailbox(accessToken);
    GmailCredentialDocument credential = new GmailCredentialDocument(); credential.id = CREDENTIAL_ID; credential.encryptedRefreshToken = crypto.encrypt(refreshToken, properties.getTokenEncryptionKey()); credential.mailbox = mailbox; credential.scopes = List.of(READ_ONLY_SCOPE); credential.connectedAt = Instant.now(); credential.connectedBy = state.actor; credentials.save(credential);
    audit.record(state.actor, "GMAIL_CONNECTED", "gmail_credential", CREDENTIAL_ID, Map.of(), Map.of("mailbox", mailbox, "scope", READ_ONLY_SCOPE));
    return new ApiDtos.GmailOAuthCallbackResponse(true, "Gmail read-only connection established");
  }

  public ApiDtos.GmailOAuthStatusResponse status() {
    boolean configured = isConfigured(); var credential = credentials.findById(CREDENTIAL_ID);
    return new ApiDtos.GmailOAuthStatusResponse(configured, configured && credential.isPresent(), properties.getMonitoredMailbox(), properties.getSenderFilter(), credential.map(c -> c.connectedAt).orElse(null), configured ? "gmail-read-only" : "disabled");
  }

  public void disconnect(String actor) { if (credentials.existsById(CREDENTIAL_ID)) { credentials.deleteById(CREDENTIAL_ID); audit.record(actor, "GMAIL_DISCONNECTED", "gmail_credential", CREDENTIAL_ID, Map.of("connected", true), Map.of("connected", false)); } }

  public String accessToken() {
    requireConfigured(); GmailCredentialDocument credential = credentials.findById(CREDENTIAL_ID).orElseThrow(() -> new IllegalStateException("Gmail is not connected"));
    String refresh = crypto.decrypt(credential.encryptedRefreshToken, properties.getTokenEncryptionKey()); Map<String, String> token = postForm(properties.getTokenEndpoint(), Map.of("client_id", properties.getClientId(), "client_secret", properties.getClientSecret(), "refresh_token", refresh, "grant_type", "refresh_token")); String access = token.get("access_token"); if (access == null || access.isBlank()) throw new IllegalStateException("Gmail access token refresh failed"); return access;
  }

  private GmailOAuthStateDocument consumeState(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("OAuth state is invalid or expired");
    Query query = Query.query(new Criteria().andOperator(Criteria.where("stateHash").is(crypto.hash(value)), Criteria.where("used").is(false), Criteria.where("expiresAt").gt(Instant.now())));
    Update update = new Update().set("used", true).set("usedAt", Instant.now()); GmailOAuthStateDocument state = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), GmailOAuthStateDocument.class);
    if (state == null) throw new IllegalArgumentException("OAuth state is invalid or expired"); return state;
  }
  private Map<String, String> exchangeCode(String code, String verifier) { return postForm(properties.getTokenEndpoint(), Map.of("client_id", properties.getClientId(), "client_secret", properties.getClientSecret(), "code", code, "code_verifier", verifier, "redirect_uri", properties.getRedirectUri(), "grant_type", "authorization_code")); }
  private Map<String, String> postForm(String endpoint, Map<String, String> values) {
    try { HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(values))).build(); HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); JsonNode body = mapper.readTree(response.body()); if (response.statusCode() / 100 != 2 || body == null || body.has("error")) throw new IllegalStateException("Google OAuth request failed"); java.util.HashMap<String, String> result = new java.util.HashMap<>(); body.fields().forEachRemaining(entry -> { if (entry.getValue().isTextual()) result.put(entry.getKey(), entry.getValue().textValue()); }); return result; } catch (java.io.IOException ex) { throw new IllegalStateException("Google OAuth request failed", ex); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Google OAuth request failed", ex); } catch (IllegalArgumentException ex) { throw new IllegalStateException("Google OAuth request failed", ex); }
  }
  private String verifyMailbox(String accessToken) {
    try { String encoded = URLEncoder.encode(properties.getMonitoredMailbox(), StandardCharsets.UTF_8); HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiBaseUrl() + "/users/" + encoded + "/profile")).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).GET().build(); HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) throw new IllegalStateException("Gmail mailbox verification failed"); JsonNode body = mapper.readTree(response.body()); String email = body.path("emailAddress").asText(""); if (email.isBlank() || !email.equalsIgnoreCase(properties.getMonitoredMailbox())) throw new IllegalStateException("OAuth account does not match monitored mailbox"); return email; } catch (java.io.IOException ex) { throw new IllegalStateException("Gmail mailbox verification failed", ex); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Gmail mailbox verification failed", ex); } catch (IllegalArgumentException ex) { throw new IllegalStateException("Gmail mailbox verification failed", ex); }
  }
  private void requireConfigured() { if (!properties.isEnabled() || !isConfigured()) throw new IllegalStateException("Gmail OAuth is not configured"); }
  private boolean isConfigured() { return properties.isEnabled() && properties.getStateTtlSeconds() > 0 && properties.getStateTtlSeconds() <= 900 && nonBlank(properties.getClientId()) && nonBlank(properties.getClientSecret()) && nonBlank(properties.getRedirectUri()) && nonBlank(properties.getTokenEncryptionKey()) && nonBlank(properties.getMonitoredMailbox()) && properties.getMonitoredMailbox().contains("@"); }
  private String randomValue(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
  private byte[] sha256(String value) { try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); } }
  private String form(Map<String, String> values) { return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse(""); }
  private boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
