package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.NotificationLogDocument;
import com.fundmanagement.api.repository.NotificationLogRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private final NotificationSender sender; private final NotificationLogRepository logs;
  public NotificationService(NotificationSender sender, NotificationLogRepository logs) { this.sender = sender; this.logs = logs; }
  public void send(String memberId, String type, NotificationSender.NotificationMessage message) {
    if (logs.findByIdempotencyKey(message.idempotencyKey()).isPresent()) return;
    NotificationLogDocument log = new NotificationLogDocument(); log.idempotencyKey = message.idempotencyKey(); log.memberId = memberId; log.type = type; log.channel = "email"; log.status = "FAILED";
    if (!sender.enabled()) { log.status = "DISABLED"; log.errorCode = "SMTP_NOT_CONFIGURED"; logs.save(log); return; }
    try { sender.send(message); log.status = "SENT"; log.sentAt = Instant.now(); } catch (RuntimeException ex) { log.errorCode = "EMAIL_SEND_FAILED"; }
    logs.save(log);
  }
}
