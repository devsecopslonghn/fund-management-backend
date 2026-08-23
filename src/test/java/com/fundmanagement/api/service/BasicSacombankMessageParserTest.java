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
}
