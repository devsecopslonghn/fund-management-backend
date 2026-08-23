package com.fundmanagement.api.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Safe default until SMTP is explicitly configured and enabled. */
@Component
@ConditionalOnMissingBean(NotificationSender.class)
public class NoopNotificationSender implements NotificationSender {
  @Override public boolean enabled() { return false; }
  @Override public void send(NotificationMessage message) { /* intentionally no network side effect */ }
}
