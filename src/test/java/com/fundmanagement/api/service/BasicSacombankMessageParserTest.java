package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BasicSacombankMessageParserTest {
  private final BasicSacombankMessageParser parser = new BasicSacombankMessageParser();
  @Test void parsesSyntheticIncomingFixtureWithoutPersistingRawDataInResponse() {
    var result = parser.parse("direction=IN\namount=1,000,000\ncontent=QUY A001 082026\noccurredAt=2026-08-24T00:00:00Z");
    assertThat(result.amount()).isEqualTo(1_000_000L); assertThat(result.direction()).isEqualTo("IN"); assertThat(result.parsedContent()).contains("A001"); assertThat(result.occurredAt().toString()).startsWith("2026-08-24");
  }
  @Test void rejectsUnrecognizedAmount() { assertThatThrownBy(() -> parser.parse("direction=IN\ncontent=unknown")).isInstanceOf(IllegalArgumentException.class); }
  @Test void parsesSyntheticSacombankBilingualAlert() {
    var result = parser.parse("Account: 0660000000; Date: 24/08/2026 14:30; Amount: + 100,000 VND; Available balance: 900,000 VND; Description: QUY A001 082026");
    assertThat(result.accountNumber()).isEqualTo("0660000000"); assertThat(result.amount()).isEqualTo(100_000L); assertThat(result.direction()).isEqualTo("IN");
    assertThat(result.availableBalance()).isEqualTo(900_000L); assertThat(result.parsedContent()).isEqualTo("QUY A001 082026");
    assertThat(result.occurredAt()).isEqualTo(java.time.Instant.parse("2026-08-24T07:30:00Z"));
  }
  @Test void decodesSacombankHtmlMimeAlertBeforeParsing() {
    var raw = "MIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"fund\"\r\n\r\n"
        + "--fund\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<table><tr><td>Tài khoản/Account</td><td>066007102000</td></tr>"
        + "<tr><td>Ngày/Date</td><td>24/08/2026 09:08</td></tr>"
        + "<tr><td>Phát sinh/Transaction</td><td>+ 100,000 VND</td></tr>"
        + "<tr><td>Số dư khả dụng/Available balance</td><td>198,000 VND</td></tr>"
        + "<tr><td>Nội dung/Description</td><td>1435784442727 0332792848 5H4Z0PO787 CKN</td></tr></table>\r\n"
        + "--fund--\r\n";
    var result = parser.parse(raw);
    assertThat(result.accountNumber()).isEqualTo("066007102000"); assertThat(result.amount()).isEqualTo(100_000L); assertThat(result.direction()).isEqualTo("IN");
    assertThat(result.parsedContent()).contains("5H4Z0PO787"); assertThat(result.availableBalance()).isEqualTo(198_000L);
    assertThat(result.occurredAt()).isEqualTo(java.time.Instant.parse("2026-08-24T02:08:00Z"));
  }
  @Test void parsesBilingualLabelsWhenHtmlPlacesEachCellOnItsOwnLine() {
    var raw = "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "Tài khoản/Account\r\n066007102000\r\n"
        + "Ngày/Date\r\n24/08/2026 09:08\r\n"
        + "Phát sinh/Transaction\r\n+ 100,000 VND\r\n"
        + "Số dư khả dụng/Available balance\r\n198,000 VND\r\n"
        + "Nội dung/Description\r\nEWX615212750 QUY LONGHN42244 082026\r\n";
    var result = parser.parse(raw);
    assertThat(result.accountNumber()).isEqualTo("066007102000");
    assertThat(result.amount()).isEqualTo(100_000L);
    assertThat(result.parsedContent()).contains("EWX615212750");
    assertThat(result.availableBalance()).isEqualTo(198_000L);
  }
  @Test void parsesPlainTextBilingualLabelsWithEmphasisMarkers() {
    var raw = "Tài khoản/*Account*\n066007102000\n"
        + "Ngày/*Date*\n25/08/2026 17:52\n"
        + "Phát sinh/*Transaction*\n+ 100,000 VND\n"
        + "Số dư khả dụng/*Available balance*\n1,198,000 VND\n"
        + "Nội dung/*Description*\nEWX619455558 HO NGOC LONG chuyen tien\n";
    var result = parser.parse(raw);
    assertThat(result.accountNumber()).isEqualTo("066007102000");
    assertThat(result.amount()).isEqualTo(100_000L);
    assertThat(result.direction()).isEqualTo("IN");
    assertThat(result.parsedContent()).contains("HO NGOC LONG");
  }
  @Test void parsesPlainTextBilingualLabelsWithClosingMarkupMarker() {
    var result = parser.parse("Tài khoản/*Account*/ 066007102000\n"
        + "Ngày/*Date*/ 25/08/2026 17:52\n"
        + "Phát sinh/*Transaction*/ + 100,000 VND\n"
        + "Nội dung/*Description*/ EWX619455558 HO NGOC LONG chuyen tien");
    assertThat(result.accountNumber()).isEqualTo("066007102000");
    assertThat(result.amount()).isEqualTo(100_000L);
    assertThat(result.direction()).isEqualTo("IN");
  }
  @Test void parsesSacombankMarkdownTableVariant() {
    var result = parser.parse("| Tài khoản/*Account* | 066007102000 |\n"
        + "| Ngày/*Date* | 25/08/2026 17:52 |\n"
        + "| Phát sinh/*Transaction* | + 100,000 VND |\n"
        + "| Nội dung/*Description* | EWX619455558 HO NGOC LONG chuyen tien |");
    assertThat(result.accountNumber()).isEqualTo("066007102000");
    assertThat(result.amount()).isEqualTo(100_000L);
    assertThat(result.direction()).isEqualTo("IN");
  }
  @Test void decodesHtmlEntitiesBeforeParsingAccountLabel() {
    var result = parser.parse("Tài khoản&#x2F;*Account*&#x2F; 066007102000\n"
        + "Phát sinh&#x2F;*Transaction*&#x2F; + 100,000 VND\n"
        + "Nội dung&#x2F;*Description*&#x2F; EWX619455558 HO NGOC LONG chuyen tien");
    assertThat(result.accountNumber()).isEqualTo("066007102000");
    assertThat(result.amount()).isEqualTo(100_000L);
  }
}
