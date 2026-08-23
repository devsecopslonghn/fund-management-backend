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
}
