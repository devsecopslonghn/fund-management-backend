package com.fundmanagement.api.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP adapter. Credentials are provided by Spring Mail/Secret injection, never by the API. */
@Component
@ConditionalOnExpression("'${app.fund.smtp-enabled:false}' == 'true' and '${app.fund.smtp-secret-ref:}' != ''")
public class SmtpNotificationSender implements NotificationSender {
  private final JavaMailSender sender;
  public SmtpNotificationSender(JavaMailSender sender) { this.sender = sender; }
  @Override public void send(NotificationMessage message) { SimpleMailMessage mail = new SimpleMailMessage(); mail.setTo(message.recipient()); mail.setSubject(message.subject()); mail.setText(message.body()); sender.send(mail); }
}
