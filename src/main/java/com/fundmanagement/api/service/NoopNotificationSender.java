package com.fundmanagement.api.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Safe default until SMTP is explicitly configured and enabled. */
@Component
@ConditionalOnProperty(prefix = "app.fund", name = "smtp-enabled", havingValue = "false", matchIfMissing = true)
public class NoopNotificationSender implements NotificationSender {
  @Override public boolean enabled() { return false; }
  @Override public void send(NotificationMessage message) { /* intentionally no network side effect */ }
}
