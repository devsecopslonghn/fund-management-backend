package com.fundmanagement.api.service;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Safe default: no network calls and no mailbox mutation. */
@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMailboxIngestionPort implements MailboxIngestionPort {
  @Override public List<MailboxMessage> fetchSacombankMessages() { return List.of(); }
}
