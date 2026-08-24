package com.fundmanagement.api.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import org.springframework.stereotype.Component;

/** Conservative parser for redacted key/value and Sacombank bilingual alert variants. */
@Component
public class BasicSacombankMessageParser implements SacombankMessageParser {
  private static final Pattern ACCOUNT = Pattern.compile("(?:account(?:\\s*(?:number|no\\.?))?|tài khoản|so tk)\\s*[:=]?\\s*([0-9][0-9 .-]{5,30})", Pattern.CASE_INSENSITIVE);
  private static final Pattern LABELED_AMOUNT = Pattern.compile("(?:amount|số tiền|transaction amount)\\s*[:=]?\\s*([+-]?)\\s*([0-9][0-9.,]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SIGNED_AMOUNT = Pattern.compile("([+-])\\s*([0-9][0-9.,]*)\\s*(?:VND|đ)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CONTENT = Pattern.compile("(?:content|nội dung|description)\\s*[:=]?\\s*([^\\r\\n<;]{1,500})", Pattern.CASE_INSENSITIVE);
  private static final Pattern TIME = Pattern.compile("(?:occurredAt|transaction time|thời gian|date|ngày(?: giờ)?)\\s*[:=]?\\s*([^\\r\\n<;]{1,80})", Pattern.CASE_INSENSITIVE);
  private static final Pattern BALANCE = Pattern.compile("(?:available balance|số dư khả dụng|available)\\s*[:=]?\\s*([0-9][0-9.,]*)", Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter SACOMBANK_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  @Override public ParsedBankMessage parse(String rawPayload) {
    if (rawPayload == null || rawPayload.isBlank()) throw new IllegalArgumentException("Empty bank message");
    String raw = readablePayload(rawPayload).replace("&nbsp;", " ").replaceAll("<[^>]*>", " ");
    Matcher labeled = LABELED_AMOUNT.matcher(raw); Matcher signed = SIGNED_AMOUNT.matcher(raw);
    String sign = null; String amountText = null;
    if (labeled.find()) { sign = labeled.group(1); amountText = labeled.group(2); }
    else if (signed.find()) { sign = signed.group(1); amountText = signed.group(2); }
    long amount = number(amountText); if (amount <= 0) throw new IllegalArgumentException("Bank amount was not recognized");
    String lower = raw.toLowerCase(Locale.ROOT);
    String direction = "-".equals(sign) || lower.contains("direction=out") || lower.contains("outgoing") || lower.contains("ghi nợ") ? "OUT" : "IN";
    String account = digits(match(raw, ACCOUNT));
    String content = clean(match(raw, CONTENT));
    Instant occurredAt = parseTime(clean(match(raw, TIME)));
    Long availableBalance = nullableNumber(match(raw, BALANCE));
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
  private Instant parseTime(String value) {
    if (value == null) return null;
    value = value.replaceFirst("(?i)^(?:date|ngày)\\s*[:/]?\\s*", "");
    Matcher sacombankDate = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2})").matcher(value);
    if (sacombankDate.find()) value = sacombankDate.group(1);
    try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
    try { return LocalDateTime.parse(value, SACOMBANK_TIME).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(); } catch (DateTimeParseException ignored) { return null; }
  }

  /** Gmail API returns RFC 822/MIME raw messages; extract decoded text parts before parsing. */
  private String readablePayload(String rawPayload) {
    if (!rawPayload.regionMatches(true, 0, "MIME-Version:", 0, "MIME-Version:".length())
        && !rawPayload.contains("\nContent-Type:") && !rawPayload.contains("\r\nContent-Type:")) return rawPayload;
    try {
      Message message = new jakarta.mail.internet.MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(rawPayload.getBytes(StandardCharsets.UTF_8)));
      StringBuilder text = new StringBuilder();
      appendText(message, text);
      return text.isEmpty() ? rawPayload : text.toString();
    } catch (MessagingException | IOException ignored) {
      return rawPayload;
    }
  }

  private void appendText(Part part, StringBuilder text) throws MessagingException, IOException {
    Object content = part.getContent();
    if (content instanceof String value) {
      text.append(value).append('\n');
    } else if (content instanceof Multipart multipart) {
      for (int index = 0; index < multipart.getCount(); index++) {
        BodyPart bodyPart = multipart.getBodyPart(index);
        if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) appendText(bodyPart, text);
      }
    }
  }
}
