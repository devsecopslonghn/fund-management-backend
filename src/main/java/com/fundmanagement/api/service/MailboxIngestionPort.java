package com.fundmanagement.api.service;

import java.util.List;

/** OAuth/read-only mailbox boundary. A Gmail adapter can be supplied without coupling it to reconciliation. */
public interface MailboxIngestionPort {
  List<MailboxMessage> fetchUnreadSacombankMessages();
  record MailboxMessage(String providerMessageId, String rawPayload) {}
}
