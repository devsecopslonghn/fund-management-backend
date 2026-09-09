package com.fundmanagement.api.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Conservative parser for redacted key/value and Sacombank bilingual alert variants. */
@Component
public class BasicSacombankMessageParser implements SacombankMessageParser {
  private static final Pattern ACCOUNT = Pattern.compile("(?:account(?:\\s*(?:number|no\\.?))?|tài khoản(?:\\s+(?:thụ hưởng|nhận|đích|giao dịch))?|số tk|so tk)\\s*[:=|]?\\s*([0-9][0-9 .-]{9,30})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  /** VIB transfer alerts contain both source and destination accounts; the destination is the receiving account. */
  private static final Pattern DESTINATION_ACCOUNT = Pattern.compile("(?:đến tài khoản|to account|destination account)\\s*[:=|]?\\s*([0-9][0-9 .-]{9,30})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern LABELED_AMOUNT = Pattern.compile("(?:amount|số tiền(?:\\s+giao dịch)?|transaction amount|transaction|phát sinh|credit amount|debit amount|ghi có|ghi nợ)\\s*[:=|]?\\s*([+-]?)\\s*([0-9][0-9.,]*)\\s*(?:VND|VNĐ|đ|đồng)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern SIGNED_AMOUNT = Pattern.compile("([+-])\\s*([0-9][0-9.,]*)\\s*(?:VND|VNĐ|đ|đồng)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern CONTENT = Pattern.compile("(?:content|nội dung|description|diễn giải)\\s*[:=]?\\s*([^\\r\\n<;]{1,500})", Pattern.CASE_INSENSITIVE);
  private static final Pattern TIME = Pattern.compile("(?:occurredAt|transaction time|thời gian|date|ngày(?: giờ)?)\\s*[:=]?\\s*([^\\r\\n<;]{1,80})", Pattern.CASE_INSENSITIVE);
  private static final Pattern VIB_TIME_LABEL = Pattern.compile("(?:ngày giao dịch|transaction date)\\s*[:=]?\\s*([^\\r\\n<;]{1,80})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern BALANCE = Pattern.compile("(?:available balance|số dư khả dụng|available)\\s*[:=]?\\s*([0-9][0-9.,]*)", Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter SACOMBANK_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
  private static final DateTimeFormatter VIB_DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

  @Override public ParsedBankMessage parse(String rawPayload) {
    if (rawPayload == null || rawPayload.isBlank()) throw new IllegalArgumentException("Empty bank message");
    String raw = decodeHtmlEntities(SacombankMessageText.readablePayload(rawPayload))
        // Preserve table-cell/row boundaries; bank HTML templates often put
        // the whole table on one physical line, so replacing every tag with a
        // space would make a field regex consume the following fields.
        .replaceAll("(?i)</?(?:td|tr|br|p|div|table)[^>]*>", "\n")
        .replaceAll("<[^>]*>", " ")
        .replace('|', ' ');
    // Sacombank's bilingual HTML labels can be separated from their value by
    // a line break. Collapse the label pair before applying line-oriented
    // key/value patterns so we never persist "Description" as the content.
    // Some Sacombank plain-text variants preserve Markdown-like emphasis
    // markers around the English half of a bilingual label.
    raw = raw.replaceAll("(?i)tài khoản\\s*/?\\s*\\*?account\\*?/?", "Account")
        .replaceAll("(?i)nội dung\\s*/?\\s*\\*?description\\*?/?", "Description")
        .replaceAll("(?i)ngày\\s*/?\\s*\\*?date\\*?/?", "Date")
        .replaceAll("(?i)phát sinh\\s*/?\\s*\\*?transaction\\*?/?", "Transaction")
        .replaceAll("(?i)số dư khả dụng\\s*/?\\s*\\*?available balance\\*?/?", "Available balance");
    Matcher labeled = LABELED_AMOUNT.matcher(raw); Matcher signed = SIGNED_AMOUNT.matcher(raw);
    String sign = null; String amountText = null;
    if (labeled.find()) { sign = labeled.group(1); amountText = labeled.group(2); }
    else if (signed.find()) { sign = signed.group(1); amountText = signed.group(2); }
    long amount = number(amountText); if (amount <= 0) throw new IllegalArgumentException("Bank amount was not recognized");
    String lower = raw.toLowerCase(Locale.ROOT);
    String direction = "-".equals(sign) || lower.contains("direction=out") || lower.contains("outgoing") || lower.contains("ghi nợ") ? "OUT" : "IN";
    Matcher destination = DESTINATION_ACCOUNT.matcher(raw);
    boolean hasDestination = destination.find();
    String account = digits(hasDestination ? destination.group(1) : match(raw, ACCOUNT));
    String content = clean(match(raw, CONTENT));
    Instant occurredAt = parseTime(clean(hasDestination ? match(raw, VIB_TIME_LABEL) : match(raw, TIME)));
    Long availableBalance = nullableNumber(match(raw, BALANCE));
    if (hasDestination) direction = "IN";
    return new ParsedBankMessage(occurredAt == null ? Instant.now() : occurredAt, amount, direction, account, content, availableBalance);
  }

  private long number(String value) {
    if (value == null || value.isBlank()) return 0;
    try { return Long.parseLong(value.replaceAll("[.,]", "")); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Bank amount was not recognized"); }
  }
  private Long nullableNumber(String value) { if (value == null) return null; long parsed = number(value); return parsed < 0 ? null : parsed; }
  private String match(String raw, Pattern pattern) { Matcher matcher = pattern.matcher(raw); return matcher.find() ? matcher.group(1).trim() : null; }
  private String clean(String value) { return value == null ? null : value.replaceAll("^[\\s/:]+", "").replaceAll("\\s+", " ").trim(); }
  private String digits(String value) { if (value == null) return null; String result = value.replaceAll("\\D", ""); return result.isBlank() ? null : result; }
  private String decodeHtmlEntities(String value) {
    if (value == null || value.isBlank()) return value;
    String decoded = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    Matcher numeric = Pattern.compile("&#(?:x([0-9a-fA-F]+)|([0-9]+));").matcher(decoded);
    StringBuffer result = new StringBuffer();
    while (numeric.find()) {
      try {
        int codePoint = Integer.parseInt(numeric.group(1) != null ? numeric.group(1) : numeric.group(2), numeric.group(1) != null ? 16 : 10);
        numeric.appendReplacement(result, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
      } catch (RuntimeException ignored) { numeric.appendReplacement(result, Matcher.quoteReplacement(numeric.group())); }
    }
    numeric.appendTail(result);
    return result.toString();
  }
  private Instant parseTime(String value) {
    if (value == null) return null;
    value = value.replaceFirst("(?i)^(?:date|ngày)\\s*[:/]?\\s*", "");
    Matcher sacombankDate = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2})").matcher(value);
    if (sacombankDate.find()) value = sacombankDate.group(1);
    try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
    try { return LocalDateTime.parse(value, SACOMBANK_TIME).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(); } catch (DateTimeParseException ignored) { }
    try { return LocalDateTime.parse(value, VIB_DATE_TIME).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(); } catch (DateTimeParseException ignored) { return null; }
  }

}
