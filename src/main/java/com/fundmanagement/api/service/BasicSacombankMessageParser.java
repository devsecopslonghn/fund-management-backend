package com.fundmanagement.api.service;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parser for redacted key/value fixtures and conservative Sacombank HTML variants. */
@Component
public class BasicSacombankMessageParser implements SacombankMessageParser {
  private static final Pattern AMOUNT = Pattern.compile("(?:amount|số tiền|transaction amount)\\s*[:=]\\s*([0-9][0-9.,]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CONTENT = Pattern.compile("(?:content|nội dung|description)\\s*[:=]\\s*([^\\r\\n<]{1,500})", Pattern.CASE_INSENSITIVE);
  private static final Pattern TIME = Pattern.compile("(?:occurredAt|transaction time|thời gian)\\s*[:=]\\s*([^\\r\\n<]{1,80})", Pattern.CASE_INSENSITIVE);
  @Override public ParsedBankMessage parse(String rawPayload) {
    if (rawPayload == null || rawPayload.isBlank()) throw new IllegalArgumentException("Empty bank message");
    String lower = rawPayload.toLowerCase(Locale.ROOT);
    String direction = lower.contains("direction=out") || lower.contains("outgoing") || lower.contains("ghi nợ") ? "OUT" : "IN";
    long amount = number(rawPayload, AMOUNT); if (amount <= 0) throw new IllegalArgumentException("Bank amount was not recognized");
    String content = match(rawPayload, CONTENT); Instant occurredAt = parseTime(match(rawPayload, TIME));
    return new ParsedBankMessage(occurredAt == null ? Instant.now() : occurredAt, amount, direction, null, content, null);
  }
  private long number(String raw, Pattern pattern) { String value = match(raw, pattern); if (value == null) return 0; try { return Long.parseLong(value.replaceAll("[.,]", "")); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Bank amount was not recognized"); } }
  private String match(String raw, Pattern pattern) { Matcher matcher = pattern.matcher(raw); return matcher.find() ? matcher.group(1).trim() : null; }
  private Instant parseTime(String value) { if (value == null) return null; try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; } }
}
