package com.fundmanagement.api.service;

import java.time.Instant;

public interface SacombankMessageParser {
  ParsedBankMessage parse(String rawPayload);
  record ParsedBankMessage(Instant occurredAt, long amount, String direction, String accountNumber, String parsedContent, Long availableBalance) {}
}
