package com.fundmanagement.api.service;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Shared MIME decoding for parsing and receiving-account validation. */
final class SacombankMessageText {
  private SacombankMessageText() {}

  static String readablePayload(String rawPayload) {
    if (!rawPayload.regionMatches(true, 0, "MIME-Version:", 0, "MIME-Version:".length())
        && !rawPayload.contains("\nContent-Type:") && !rawPayload.contains("\r\nContent-Type:")) return rawPayload;
    try {
      Message message = new MimeMessage(Session.getInstance(new Properties()),
          new ByteArrayInputStream(rawPayload.getBytes(StandardCharsets.UTF_8)));
      StringBuilder text = new StringBuilder();
      appendText(message, text);
      return text.isEmpty() ? rawPayload : text.toString();
    } catch (MessagingException | IOException ignored) {
      return rawPayload;
    }
  }

  private static void appendText(Part part, StringBuilder text) throws MessagingException, IOException {
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
