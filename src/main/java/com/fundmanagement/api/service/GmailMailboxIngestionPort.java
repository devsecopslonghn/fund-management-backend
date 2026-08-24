package com.fundmanagement.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Gmail API adapter with readonly scope; it never labels, marks read, deletes, or sends. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "enabled", havingValue = "true")
public class GmailMailboxIngestionPort implements MailboxIngestionPort {
  private final GmailProperties properties; private final GmailOAuthService oauth; private final ObjectMapper mapper; private final HttpClient http; private final SettingsService settings;
  public GmailMailboxIngestionPort(GmailProperties properties, GmailOAuthService oauth, ObjectMapper mapper, SettingsService settings) { this.properties = properties; this.oauth = oauth; this.mapper = mapper; this.settings = settings; this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); }
  /**
   * Reads every message matching the configured receiving account.
   *
   * <p>The query intentionally does not require an unread label. Gmail messages
   * are paged in batches of 100 so a 30-minute reconciliation run can catch
   * alerts that arrived while the mailbox was already opened or while the
   * application was unavailable. Provider message IDs make repeated scans
   * idempotent in {@link IngestionService}.
   */
  @Override public List<MailboxMessage> fetchSacombankMessages() {
    String token = oauth.accessToken(); String mailbox = URLEncoder.encode(properties.getMonitoredMailbox(), StandardCharsets.UTF_8);
    String expectedAccount = settings.get().accountNumber() == null ? "" : settings.get().accountNumber().replaceAll("\\D", "");
    if (expectedAccount.isBlank()) return List.of();
    String query = URLEncoder.encode(gmailSearchQuery(expectedAccount), StandardCharsets.UTF_8);
    List<MailboxMessage> messages = new ArrayList<>();
    String pageToken = "";
    do {
      String url = properties.getApiBaseUrl() + "/users/" + mailbox + "/messages?maxResults=100&q=" + query;
      if (!pageToken.isBlank()) url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
      JsonNode list = get(url, token);
      for (JsonNode item : list.path("messages")) {
        String id = item.path("id").asText("");
        if (!id.isBlank()) messages.add(new MailboxMessage(id, rawMessage(mailbox, id, token)));
      }
      pageToken = list.path("nextPageToken").asText("");
    } while (!pageToken.isBlank());
    return messages;
  }
  static String gmailSearchQuery(String accountNumber) {
    String digits = accountNumber == null ? "" : accountNumber.replaceAll("\\D", "");
    if (digits.isBlank()) throw new IllegalArgumentException("Configured receiving account is required");
    // Match all Sacombank alert variants while requiring the configured
    // receiving account. There is intentionally no unread/state predicate.
    return "(\"Sacombank\" OR \"Ngân hàng TMCP Sài Gòn Thương Tín\" OR from:sacombank.com.vn) \"" + digits + "\"";
  }
  private String rawMessage(String mailbox, String id, String token) { JsonNode body = get(properties.getApiBaseUrl() + "/users/" + mailbox + "/messages/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?format=raw", token); String raw = body.path("raw").asText(""); if (raw.isBlank()) throw new IllegalStateException("Gmail message payload was empty"); try { return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8); } catch (IllegalArgumentException ex) { throw new IllegalStateException("Gmail message payload was invalid"); } }
  private JsonNode get(String url, String token) { try { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(); HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) throw new IllegalStateException("Gmail API request failed"); return mapper.readTree(response.body()); } catch (java.io.IOException ex) { throw new IllegalStateException("Gmail API request failed", ex); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Gmail API request interrupted", ex); } }
}
