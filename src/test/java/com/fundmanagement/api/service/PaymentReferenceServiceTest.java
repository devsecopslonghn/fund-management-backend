package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fundmanagement.api.domain.PaymentReferenceDocument;
import com.fundmanagement.api.repository.PaymentReferenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentReferenceServiceTest {
  @Test void createsOpaqueTenCharacterCodeAndReusesMemberMonthReference() {
    PaymentReferenceRepository repository = mock(PaymentReferenceRepository.class); PaymentReferenceDocument stored = new PaymentReferenceDocument();
    when(repository.findByMemberIdAndMonth("member-a", "2026-08")).thenReturn(Optional.empty(), Optional.of(stored));
    when(repository.insert(any(PaymentReferenceDocument.class))).thenAnswer(invocation -> { PaymentReferenceDocument value = invocation.getArgument(0); stored.code = value.code; return value; });
    PaymentReferenceService service = new PaymentReferenceService(repository);

    String first = service.referenceFor("member-a", "2026-08"); String second = service.referenceFor("member-a", "2026-08");

    assertThat(first).matches("[A-Z0-9]{10}"); assertThat(second).isEqualTo(first); verify(repository).insert(any(PaymentReferenceDocument.class));
  }
}
