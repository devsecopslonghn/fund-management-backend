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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import com.fundmanagement.api.repository.BankTransactionRepository;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gmail API adapter with readonly scope; it never labels, marks read, deletes, or sends. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "enabled", havingValue = "true")
public class GmailMailboxIngestionPort implements MailboxIngestionPort {
  private static final Logger log = LoggerFactory.getLogger(GmailMailboxIngestionPort.class);
  private static final String RECENT_MAIL_QUERY = "newer_than:30d";
  private static final int MAX_MESSAGE_LIST_PAGES = 10;
  private final GmailProperties properties; private final GmailOAuthService oauth; private final ObjectMapper mapper; private final HttpClient http; private final SettingsService settings; private final BankTransactionRepository transactions; private final SacombankMessageParser parser;
  public GmailMailboxIngestionPort(GmailProperties properties, GmailOAuthService oauth, ObjectMapper mapper, SettingsService settings, BankTransactionRepository transactions, SacombankMessageParser parser) { this.properties = properties; this.oauth = oauth; this.mapper = mapper; this.settings = settings; this.transactions = transactions; this.parser = parser; this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); }

  /**
   * Read-only diagnostic for one Gmail thread. It deliberately does not return
   * raw MIME, persist anything, or call the ingestion/reconciliation flow.
   */
  public List<GmailDiagnosticMessage> diagnosticMessages(String requestedMessageId, String requestedThreadId) {
    boolean hasMessage = requestedMessageId != null && !requestedMessageId.isBlank();
    boolean hasThread = requestedThreadId != null && !requestedThreadId.isBlank();
    if (hasMessage == hasThread) throw new IllegalArgumentException("Exactly one Gmail messageId or threadId is required");
    String token = oauth.accessToken();
    String mailbox = URLEncoder.encode(properties.getMonitoredMailbox(), StandardCharsets.UTF_8);
    String threadId = hasThread ? providerId(requestedThreadId) : "";
    Map<String, GmailMessageMetadata> metadata = new LinkedHashMap<>();
    if (hasMessage) {
      String messageId = providerId(requestedMessageId);
      JsonNode message = get(messageUrl(mailbox, messageId, "minimal"), token);
      threadId = providerId(message.path("threadId").asText(""));
      metadata.put(messageId, metadata(message, threadId));
    }
    if (threadId.isBlank()) throw new IllegalArgumentException("Gmail message did not contain a threadId");
    JsonNode thread = get(properties.getApiBaseUrl() + "/users/" + mailbox + "/threads/" +
        URLEncoder.encode(threadId, StandardCharsets.UTF_8) + "?format=minimal", token);
    for (JsonNode message : thread.path("messages")) {
      String messageId = message.path("id").asText("");
      if (!messageId.isBlank()) metadata.put(messageId, metadata(message, threadId));
    }
    if (metadata.isEmpty()) throw new IllegalArgumentException("Gmail thread did not contain messages");
    if (hasMessage && !metadata.containsKey(providerId(requestedMessageId))) {
      throw new IllegalArgumentException("Gmail message was not found in the requested thread");
    }
    String expectedAccount = settings.get().accountNumber() == null ? "" : settings.get().accountNumber().replaceAll("\\D", "");
    List<GmailDiagnosticMessage> result = new ArrayList<>();
    for (Map.Entry<String, GmailMessageMetadata> entry : metadata.entrySet()) {
      byte[] rawBytes = rawMessageBytes(mailbox, entry.getKey(), token);
      result.add(diagnose(entry.getKey(), entry.getValue(), rawBytes, expectedAccount));
    }
    return result;
  }

  public record GmailDiagnosticMessage(String messageId, String threadId, Instant gmailInternalDate,
      Instant mimeDate, int rawLength, List<GmailMimePart> mimeParts, String parserError,
      int parsedAccountDigits, String parsedAccountMasked, boolean parsedAccountMatchesConfigured,
      Long parsedAmount, String parsedDirection, String parsedContent) {}

  public record GmailMimePart(String path, String contentType, String charset,
      String transferEncoding, String disposition, int declaredSize) {}

  private record GmailMessageMetadata(String threadId, Instant gmailInternalDate) {}
  private record FetchResult(List<MailboxMessage> messages, int rawFetched, int rejected) {}

  private GmailDiagnosticMessage diagnose(String messageId, GmailMessageMetadata metadata, byte[] rawBytes, String expectedAccount) {
    MimeMessage mime = null;
    List<GmailMimePart> parts = new ArrayList<>();
    Instant mimeDate = null;
    String mimeError = null;
    try {
      mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(rawBytes));
      Date sentDate = mime.getSentDate();
      mimeDate = sentDate == null ? null : sentDate.toInstant();
      inspectMimePart(mime, "1", parts);
    } catch (MessagingException | IOException error) {
      mimeError = error.getClass().getSimpleName();
    }
    SacombankMessageParser.ParsedBankMessage parsed = null;
    String parserError = null;
    try {
      parsed = parser.parse(new String(rawBytes, StandardCharsets.UTF_8));
    } catch (RuntimeException error) {
      parserError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    if (mimeError != null && parserError == null) parserError = "MIME: " + mimeError;
    String account = parsed == null ? null : parsed.accountNumber();
    String digits = account == null ? "" : account.replaceAll("\\D", "");
    String parsedContent = parsed == null ? null : redact(parsed.parsedContent(), expectedAccount, digits);
    return new GmailDiagnosticMessage(messageId, metadata.threadId(), metadata.gmailInternalDate(), mimeDate,
        rawBytes.length, List.copyOf(parts), parserError, digits.length(), maskAccount(digits),
        !digits.isBlank() && digits.equals(expectedAccount), parsed == null ? null : parsed.amount(),
        parsed == null ? null : parsed.direction(), parsedContent);
  }

  private void inspectMimePart(Part part, String path, List<GmailMimePart> parts) throws MessagingException, IOException {
    Object content = part.getContent();
    if (content instanceof Multipart multipart) {
      for (int index = 0; index < multipart.getCount(); index++) {
        inspectMimePart(multipart.getBodyPart(index), path + "." + (index + 1), parts);
      }
      return;
    }
    String contentType = part.getContentType();
    parts.add(new GmailMimePart(path, baseContentType(contentType), parameter(contentType, "charset"),
        firstHeader(part, "Content-Transfer-Encoding"), firstHeader(part, "Content-Disposition"),
        Math.max(0, part.getSize())));
  }

  private String baseContentType(String contentType) {
    if (contentType == null) return null;
    int separator = contentType.indexOf(';');
    return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(java.util.Locale.ROOT);
  }

  private String parameter(String contentType, String name) {
    if (contentType == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*" + name + "\\s*=\\s*(?:\\\"([^\\\"]+)\\\"|([^;\\s]+))").matcher(contentType);
    return matcher.find() ? (matcher.group(1) == null ? matcher.group(2) : matcher.group(1)) : null;
  }

  private String firstHeader(Part part, String name) throws MessagingException {
    String[] values = part.getHeader(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  private String redact(String value, String expectedAccount, String parsedAccount) {
    if (value == null) return null;
    String result = value;
    for (String account : List.of(expectedAccount, parsedAccount)) {
      if (account != null && account.length() >= 8) result = result.replace(account, maskAccount(account));
    }
    return result.replaceAll("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", "[email-redacted]")
        .replaceAll("(?<!\\d)\\d[\\d .-]{7,}\\d(?!\\d)", "[account-redacted]");
  }

  private String maskAccount(String digits) {
    if (digits == null || digits.isBlank()) return null;
    return digits.substring(0, Math.min(2, digits.length())) + "…" + digits.substring(Math.max(0, digits.length() - 4));
  }

  private GmailMessageMetadata metadata(JsonNode message, String fallbackThreadId) {
    String threadId = message.path("threadId").asText(fallbackThreadId);
    String internalDate = message.path("internalDate").asText("");
    Instant timestamp = internalDate.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(internalDate));
    return new GmailMessageMetadata(threadId, timestamp);
  }

  private String providerId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException("Gmail provider identifier is invalid");
    return value;
  }

  /**
   * Reads every message matching the configured receiving account.
   *
   * <p>The query intentionally does not require an unread label. Gmail messages
   * are paged in batches of 100 so the periodic reconciliation run can catch
   * alerts that arrived while the mailbox was already opened or while the
   * application was unavailable. Provider message IDs make repeated scans
   * idempotent in {@link IngestionService}.
   */
  @Override public List<MailboxMessage> fetchSacombankMessages() {
    String token = oauth.accessToken(); String mailbox = URLEncoder.encode(properties.getMonitoredMailbox(), StandardCharsets.UTF_8);
    String expectedAccount = settings.get().accountNumber() == null ? "" : settings.get().accountNumber().replaceAll("\\D", "");
    if (expectedAccount.isBlank()) return List.of();
    List<String> candidateIds = listMessageIds(mailbox, token, gmailSearchQuery(expectedAccount));
    // Some forwarded bank alerts are not indexed by Gmail's bank/sender
    // query. Always merge the bounded recent-mail scan; Gmail can return old
    // indexed alerts while omitting a newly forwarded MIME message.
    candidateIds.addAll(listMessageIds(mailbox, token, RECENT_MAIL_QUERY));
    candidateIds = new ArrayList<>(new LinkedHashSet<>(candidateIds));
    if (candidateIds.isEmpty()) return List.of();
    // The list endpoint is cheap; raw MIME retrieval is not. Repeated 5s/30m
    // scans therefore fetch only provider IDs not already persisted locally.
    Set<String> known = transactions.findByProviderMessageIdIn(candidateIds).stream()
        .map(tx -> tx.providerMessageId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    FetchResult result = fetchNewMessages(mailbox, token, candidateIds, known);
    log.info("Gmail mailbox scan candidates={} known={} rawFetched={} matched={} rejected={} configuredAccount={}",
        candidateIds.size(), known.size(), result.rawFetched(), result.messages().size(), result.rejected(), maskAccount(expectedAccount));
    return result.messages();
  }

  private List<String> listMessageIds(String mailbox, String token, String query) {
    List<String> ids = new ArrayList<>();
    String pageToken = "";
    int pages = 0;
    do {
      pages++;
      String url = properties.getApiBaseUrl() + "/users/" + mailbox + "/messages?maxResults=100";
      if (query != null && !query.isBlank()) url += "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
      if (!pageToken.isBlank()) url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
      JsonNode list = get(url, token);
      for (JsonNode item : list.path("messages")) {
        String id = item.path("id").asText("");
        if (!id.isBlank()) ids.add(id);
      }
      pageToken = list.path("nextPageToken").asText("");
    } while (!pageToken.isBlank() && pages < MAX_MESSAGE_LIST_PAGES);
    if (!pageToken.isBlank()) log.warn("Gmail message listing stopped at page limit queryType={} pages={}",
        query.equals(RECENT_MAIL_QUERY) ? "recent" : "bank", pages);
    return new ArrayList<>(new LinkedHashSet<>(ids));
  }

  /** Fetches messages added after a Gmail history cursor for Pub/Sub delivery. */
  public List<MailboxMessage> fetchMessagesSinceHistoryId(String historyId) {
    if (historyId == null || historyId.isBlank()) throw new IllegalArgumentException("Gmail history cursor is required");
    String token = oauth.accessToken();
    String mailbox = URLEncoder.encode(properties.getMonitoredMailbox(), StandardCharsets.UTF_8);
    Set<String> ids = new LinkedHashSet<>();
    String pageToken = "";
    do {
      String url = properties.getApiBaseUrl() + "/users/" + mailbox + "/history?startHistoryId=" + URLEncoder.encode(historyId, StandardCharsets.UTF_8)
          + "&historyTypes=messageAdded&maxResults=100";
      if (!pageToken.isBlank()) url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
      JsonNode history = get(url, token);
      for (JsonNode record : history.path("history")) {
        for (JsonNode added : record.path("messagesAdded")) {
          String id = added.path("message").path("id").asText("");
          if (!id.isBlank()) ids.add(id);
        }
        // Older Gmail responses may expose message ids directly.
        for (JsonNode message : record.path("messages")) {
          String id = message.path("id").asText("");
          if (!id.isBlank()) ids.add(id);
        }
      }
      pageToken = history.path("nextPageToken").asText("");
    } while (!pageToken.isBlank());
    if (ids.isEmpty()) return List.of();
    Set<String> known = transactions.findByProviderMessageIdIn(ids).stream()
        .map(tx -> tx.providerMessageId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    return fetchNewMessages(mailbox, token, ids, known).messages();
  }

  private FetchResult fetchNewMessages(String mailbox, String token, Collection<String> ids, Set<String> known) {
    List<MailboxMessage> messages = new ArrayList<>();
    int rawFetched = 0;
    int rejected = 0;
    int rejectionDiagnostics = 0;
    for (String id : ids) if (!known.contains(id)) {
      String raw = rawMessage(mailbox, id, token);
      rawFetched++;
      // Gmail search can match a thread because another message in that
      // thread contains the configured account. Re-check each expanded raw
      // message before handing it to ingestion.
      if (isConfiguredReceivingAlert(raw)) messages.add(new MailboxMessage(id, raw));
      else { rejected++; if (rejectionDiagnostics++ < 5) logRejectedDiagnostic(id, raw); }
    }
    return new FetchResult(List.copyOf(messages), rawFetched, rejected);
  }
  private void logRejectedDiagnostic(String id, String raw) {
    try {
      var parsed = parser.parse(raw); String expected = settings.get().accountNumber() == null ? "" : settings.get().accountNumber().replaceAll("\\D", ""); String actual = parsed.accountNumber() == null ? "" : parsed.accountNumber().replaceAll("\\D", "");
      log.warn("Gmail candidate rejected providerId={} rawLength={} parserAccountDigits={} accountMatches={} amountRecognized={} direction={} hasAccountLabel={} hasTransactionLabel={} hasDescriptionLabel={}", id, raw == null ? 0 : raw.length(), actual.length(), expected.equals(actual), parsed.amount() > 0, parsed.direction(), containsLabel(raw, "account", "tài khoản"), containsLabel(raw, "transaction", "phát sinh", "amount", "số tiền"), containsLabel(raw, "description", "nội dung", "diễn giải"));
    } catch (RuntimeException failure) { log.warn("Gmail candidate rejected providerId={} rawLength={} parserError={}", id, raw == null ? 0 : raw.length(), failure.getMessage()); }
  }
  private boolean containsLabel(String raw, String... labels) { String value = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT); for (String label : labels) if (value.contains(label)) return true; return false; }
  private boolean isConfiguredReceivingAlert(String raw) {
    String expected = settings.get().accountNumber() == null ? "" : settings.get().accountNumber().replaceAll("\\D", "");
    if (expected.isBlank()) return false;
    try {
      SacombankMessageParser.ParsedBankMessage parsed = parser.parse(raw);
      String actual = parsed.accountNumber() == null ? "" : parsed.accountNumber().replaceAll("\\D", "");
      if (expected.equals(actual)) return true;
    } catch (RuntimeException ignored) { }
    String normalized = SacombankMessageText.readablePayload(raw)
        .replaceAll("(?s)<[^>]*>", " ")
        .replaceAll("&#x([0-9a-fA-F]+);", " ")
        .replaceAll("&#([0-9]+);", " ")
        .replaceAll("\\D", "");
    return normalized.contains(expected);
  }
  static String gmailSearchQuery(String accountNumber) {
    String digits = accountNumber == null ? "" : accountNumber.replaceAll("\\D", "");
    if (digits.isBlank()) throw new IllegalArgumentException("Configured receiving account is required");
    // Match ordinary Sacombank alerts first. A bounded recent-mail fallback
    // handles forwarded/MIME messages that Gmail does not index by sender or
    // subject; receiving-account validation remains local and fail-closed.
    return "(\"Sacombank\" OR \"Ngân hàng TMCP Sài Gòn Thương Tín\" OR from:sacombank.com.vn)";
  }
  private String messageUrl(String mailbox, String id, String format) {
    return properties.getApiBaseUrl() + "/users/" + mailbox + "/messages/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?format=" + format;
  }
  private byte[] rawMessageBytes(String mailbox, String id, String token) {
    JsonNode body = get(messageUrl(mailbox, id, "raw"), token);
    String raw = body.path("raw").asText("");
    if (raw.isBlank()) throw new IllegalStateException("Gmail message payload was empty");
    try { return Base64.getUrlDecoder().decode(raw); }
    catch (IllegalArgumentException ex) { throw new IllegalStateException("Gmail message payload was invalid"); }
  }
  private String rawMessage(String mailbox, String id, String token) {
    return new String(rawMessageBytes(mailbox, id, token), StandardCharsets.UTF_8);
  }
  private JsonNode get(String url, String token) { try { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(); HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); JsonNode body = mapper.readTree(response.body()); if (response.statusCode() / 100 != 2) throw new IllegalStateException("Gmail API request failed: http_" + response.statusCode() + ":" + providerError(body)); return body; } catch (java.net.http.HttpTimeoutException ex) { throw new IllegalStateException("Gmail API request failed: timeout", ex); } catch (java.io.IOException ex) { throw new IllegalStateException("Gmail API request failed: transport", ex); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Gmail API request interrupted", ex); } }
  private String providerError(JsonNode body) { if (body == null) return "unknown"; String reason = body.path("error").path("errors").path(0).path("reason").asText(""); if (!reason.isBlank()) return reason; String status = body.path("error").path("status").asText(""); return status.isBlank() ? "unknown" : status.toLowerCase(java.util.Locale.ROOT); }
}
