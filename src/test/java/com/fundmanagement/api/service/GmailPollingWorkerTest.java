package com.fundmanagement.api.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.dto.ApiDtos;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailPollingWorkerTest {
  @Mock MailboxIngestionPort mailbox; @Mock IngestionService ingestion; @Mock GmailPollingLeaseService lease;
  @Test void skipsMailboxWhenAnotherReplicaOwnsLease() {
    when(lease.tryAcquire()).thenReturn(java.util.Optional.empty()); new GmailPollingWorker(mailbox, ingestion, lease).poll(); verifyNoInteractions(mailbox, ingestion);
  }
  @Test void releasesLeaseAndContinuesAfterOneMessageFailure() {
    var handle = new GmailPollingLeaseService.LeaseHandle("pod-a", "token"); when(lease.tryAcquire()).thenReturn(java.util.Optional.of(handle)); when(mailbox.fetchUnreadSacombankMessages()).thenReturn(List.of(new MailboxIngestionPort.MailboxMessage("m1", "raw1"), new MailboxIngestionPort.MailboxMessage("m2", "raw2"))); doThrow(new IllegalStateException("synthetic")).when(ingestion).ingest(new ApiDtos.BankTransactionIngestionRequest("m1", "raw1"));
    new GmailPollingWorker(mailbox, ingestion, lease).poll(); verify(ingestion).ingest(new ApiDtos.BankTransactionIngestionRequest("m2", "raw2")); verify(lease).release(handle);
  }
}
