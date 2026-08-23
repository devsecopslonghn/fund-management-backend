package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fundmanagement.api.domain.PaymentDetectionWindowDocument;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class PaymentDetectionWindowServiceTest {
  @Test void activeWindowIsReadFromMongoAndOpenUsesUpsert() {
    MongoTemplate mongo = mock(MongoTemplate.class); PaymentDetectionWindowService service = new PaymentDetectionWindowService(mongo); PaymentDetectionWindowDocument active = new PaymentDetectionWindowDocument(); active.activeUntil = Instant.now().plusSeconds(120); when(mongo.findById("gmail-fast-polling", PaymentDetectionWindowDocument.class)).thenReturn(active);
    assertThat(service.isActive()).isTrue(); service.open(java.time.Duration.ofMinutes(5)); verify(mongo).findAndModify(any(), any(), any(), eq(PaymentDetectionWindowDocument.class));
  }
}
