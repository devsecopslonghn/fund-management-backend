package com.fundmanagement.api.service;

public interface NotificationSender {
  default boolean enabled() { return true; }
  void send(NotificationMessage message);
  record NotificationMessage(String recipient, String subject, String body, String idempotencyKey) {}
}
