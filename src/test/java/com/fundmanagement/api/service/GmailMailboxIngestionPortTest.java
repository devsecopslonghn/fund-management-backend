package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GmailMailboxIngestionPortTest {
  @Test void searchAlwaysContainsTheConfiguredReceivingAccount() {
    String query = GmailMailboxIngestionPort.gmailSearchQuery("000 123-456");
    assertThat(query).contains("\"000123456\"");
    assertThat(query).contains("\"000 123 456\"");
    assertThat(query).doesNotContainIgnoringCase("unread");
  }

  @Test void searchesCommonAccountNumberSeparators() {
    assertThat(GmailMailboxIngestionPort.accountSearchVariants("066007102000"))
        .containsExactly("066007102000", "0660 0710 2000", "0660-0710-2000", "0660.0710.2000",
            "066 007 102 000", "066-007-102-000", "066.007.102.000");
  }

  @Test void searchFailsClosedWithoutAConfiguredReceivingAccount() {
    assertThatThrownBy(() -> GmailMailboxIngestionPort.gmailSearchQuery("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void onlyFetchesMessagesReturnedByAccountSearch() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    String incoming = "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getEncoder().encodeToString("Account: 066007102000\nAmount: + 100,000 VND\n".getBytes(StandardCharsets.UTF_8));
    String unrelated = "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getEncoder().encodeToString("Account: 044007102000\nAmount: + 100,000 VND\n".getBytes(StandardCharsets.UTF_8));
    server.createContext("/gmail/v1", exchange -> {
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/messages")) respond(exchange, "{\"messages\":[{\"id\":\"message-in\",\"threadId\":\"thread-1\"}]}");
      else if (path.endsWith("/threads/thread-1")) respond(exchange, "{\"messages\":[{\"id\":\"message-in\"},{\"id\":\"message-unrelated\"}]}");
      else if (path.endsWith("/messages/message-in")) respond(exchange, raw(incoming));
      else if (path.endsWith("/messages/message-unrelated")) respond(exchange, raw(unrelated));
      else respond(exchange, "{}");
    });
    server.start();
    try {
      GmailProperties properties = new GmailProperties();
      properties.setEnabled(true); properties.setMonitoredMailbox("fund@example.test");
      properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gmail/v1");
      GmailOAuthService oauth = mock(GmailOAuthService.class); when(oauth.accessToken()).thenReturn("access-token");
      SettingsService settings = mock(SettingsService.class);
      when(settings.get()).thenReturn(new ApiDtos.SettingsResponse(100_000, 0.85, "Sacombank", "9704",
          "066007102000", "Fund", false, false, true, true, "bank@example.test", false, "", 587, ""));
      BankTransactionRepository transactions = mock(BankTransactionRepository.class);
      when(transactions.findByProviderMessageIdIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.List.of());
      GmailMailboxIngestionPort port = new GmailMailboxIngestionPort(properties, oauth, new ObjectMapper(),
          settings, transactions, new BasicSacombankMessageParser());

      var result = port.fetchSacombankMessages();

      assertThat(result).extracting(MailboxIngestionPort.MailboxMessage::providerMessageId)
          .containsExactly("message-in");
    } finally {
      server.stop(0);
    }
  }

  @Test void diagnosticReadsOneThreadWithoutReturningRawMimeAndRedactsAccountAndEmail() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    String incoming = "MIME-Version: 1.0\r\n"
        + "Date: Mon, 24 Aug 2026 09:08:00 +0700\r\n"
        + "Content-Type: multipart/alternative; boundary=\"fund\"\r\n\r\n"
        + "--fund\r\nContent-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: 8bit\r\n\r\n"
        + "Account: 066007102000\nAmount: + 100,000 VND\n"
        + "Description: QUY A001 payer@example.com\n--fund--\r\n";
    String outgoing = "MIME-Version: 1.0\r\n"
        + "Date: Mon, 24 Aug 2026 09:09:00 +0700\r\n"
        + "Content-Type: text/plain; charset=ISO-8859-1\r\n"
        + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
        + "direction=OUT\namount=3D100,000\ncontent=fee\n";
    assertThat(new BasicSacombankMessageParser().parse(outgoing).direction()).isEqualTo("OUT");
    server.createContext("/gmail/v1", exchange -> respond(exchange, responseFor(exchange, incoming, outgoing)));
    server.start();
    try {
      GmailProperties properties = new GmailProperties();
      properties.setEnabled(true); properties.setMonitoredMailbox("fund@example.test");
      properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gmail/v1");
      GmailOAuthService oauth = mock(GmailOAuthService.class); when(oauth.accessToken()).thenReturn("access-token");
      SettingsService settings = mock(SettingsService.class);
      when(settings.get()).thenReturn(new ApiDtos.SettingsResponse(100_000, 0.85, "Sacombank", "9704",
          "066007102000", "Fund", false, false, true, true, "bank@example.test", false, "", 587, ""));
      GmailMailboxIngestionPort port = new GmailMailboxIngestionPort(properties, oauth, new ObjectMapper(),
          settings, mock(BankTransactionRepository.class), new BasicSacombankMessageParser());

      var result = port.diagnosticMessages("message-in", null);

      assertThat(result).hasSize(2);
      assertThat(result).extracting(GmailMailboxIngestionPort.GmailDiagnosticMessage::threadId)
          .containsOnly("thread-1");
      var first = result.getFirst();
      assertThat(first.gmailInternalDate()).isEqualTo(Instant.ofEpochMilli(1787537280000L));
      assertThat(first.mimeDate()).isEqualTo(Instant.parse("2026-08-24T02:08:00Z"));
      assertThat(first.parsedAmount()).isEqualTo(100_000L);
      assertThat(first.parsedDirection()).isEqualTo("IN");
      assertThat(first.parsedAccountMasked()).isEqualTo("06…2000");
      assertThat(first.parsedContent()).contains("QUY A001", "[email-redacted]")
          .doesNotContain("066007102000", "payer@example.com");
      assertThat(first.mimeParts()).anyMatch(part -> "text/plain".equals(part.contentType())
          && "UTF-8".equals(part.charset()) && "8bit".equalsIgnoreCase(part.transferEncoding()));
      assertThat(first.rawLength()).isGreaterThan(0);
      assertThat(result.get(1).parserError()).as("outgoing parser error").isNull();
      assertThat(result.get(1).parsedDirection()).isEqualTo("OUT");
    } finally {
      server.stop(0);
    }
  }

  private static String responseFor(HttpExchange exchange, String incoming, String outgoing) {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    if (path.endsWith("/messages/message-in") && "format=minimal".equals(query)) {
      return "{\"id\":\"message-in\",\"threadId\":\"thread-1\",\"internalDate\":\"1787537280000\"}";
    }
    if (path.endsWith("/threads/thread-1")) return "{\"messages\":["
        + "{\"id\":\"message-in\",\"threadId\":\"thread-1\",\"internalDate\":\"1787537280000\"},"
        + "{\"id\":\"message-out\",\"threadId\":\"thread-1\",\"internalDate\":\"1787537340000\"}]}";
    if (path.endsWith("/messages/message-in")) return raw(incoming);
    if (path.endsWith("/messages/message-out")) return raw(outgoing);
    return "{}";
  }

  private static String raw(String payload) {
    return "{\"raw\":\"" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "\"}";
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
