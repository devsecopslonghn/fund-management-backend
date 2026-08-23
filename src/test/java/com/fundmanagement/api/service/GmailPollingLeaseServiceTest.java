package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.GmailPollingLeaseDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class GmailPollingLeaseServiceTest {
  @Mock MongoTemplate mongo;
  @Test void atomicallyAcquiresOnlyWhenReturnedOwnerAndTokenMatch() {
    GmailProperties properties = new GmailProperties(); properties.setLeaseDurationSeconds(120); GmailPollingLeaseDocument document = lease("pod-a", "token-a");
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(GmailPollingLeaseDocument.class))).thenAnswer(invocation -> { org.bson.Document set = (org.bson.Document) invocation.getArgument(1, Update.class).getUpdateObject().get("$set"); document.leaseToken = set.getString("leaseToken"); return document; });
    var service = new GmailPollingLeaseService(mongo, properties, "pod-a");
    var acquired = service.tryAcquire();
    assertThat(acquired).isPresent(); assertThat(acquired.get().token()).isNotBlank().isNotEqualTo("token-a");
  }
  @Test void refusesLeaseOwnedByAnotherReplica() {
    GmailProperties properties = new GmailProperties(); GmailPollingLeaseDocument document = lease("pod-b", "token-b");
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(GmailPollingLeaseDocument.class))).thenReturn(document);
    assertThat(new GmailPollingLeaseService(mongo, properties, "pod-a").tryAcquire()).isEmpty();
  }
  @Test void releaseIsOwnerAndTokenGuarded() {
    GmailProperties properties = new GmailProperties(); GmailPollingLeaseDocument document = lease("pod-a", "token-a");
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(GmailPollingLeaseDocument.class))).thenReturn(document);
    var service = new GmailPollingLeaseService(mongo, properties, "pod-a");
    assertThat(service.release(new GmailPollingLeaseService.LeaseHandle("pod-a", "token-a"))).isTrue();
  }
  private GmailPollingLeaseDocument lease(String owner, String token) { GmailPollingLeaseDocument value = new GmailPollingLeaseDocument(); value.owner = owner; value.leaseToken = token; return value; }
}
