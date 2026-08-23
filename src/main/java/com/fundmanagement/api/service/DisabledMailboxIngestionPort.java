package com.fundmanagement.api.service;

import java.util.List;
import org.springframework.stereotype.Component;

/** Safe default: no network calls and no mailbox mutation. */
@Component
public class DisabledMailboxIngestionPort implements MailboxIngestionPort {
  @Override public List<MailboxMessage> fetchUnreadSacombankMessages() { return List.of(); }
}
