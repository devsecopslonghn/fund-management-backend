package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ManualPaymentDocument;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.PaymentAllocation;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
  @Mock ManualPaymentRepository payments;
  @Mock DuePeriodRepository dues;
  @Mock PaymentIntentRepository intents;
  @Mock MemberService members;
  @Mock AuditService audit;

  @Test void manuallyAllocatesOldestOutstandingPeriodsFirstAndKeepsRemainder() {
    DuePeriodDocument july = due("july", "2026-07", 100_000, 0); DuePeriodDocument august = due("aug", "2026-08", 200_000, 0); MemberDocument member = new MemberDocument(); member.id = "member-1";
    when(members.get("member-1")).thenReturn(member); when(payments.findByIdempotencyKey("unique-key-000000")).thenReturn(Optional.empty()); when(payments.insert(org.mockito.ArgumentMatchers.<ManualPaymentDocument>any())).thenAnswer(invocation -> invocation.getArgument(0)); when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(List.of(august, july)); when(intents.findByMemberId("member-1")).thenReturn(List.of());
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    ApiDtos.ManualPaymentResponse result = service.record(new ApiDtos.ManualPaymentRequest("member-1", 250_000, Instant.parse("2026-08-10T00:00:00Z"), "unique-key-000000", "cash"), "admin");

    assertThat(result.unallocatedAmount()).isZero(); assertThat(result.allocations()).extracting(ApiDtos.AllocationResponse::duePeriodId).containsExactly("july", "aug"); assertThat(july.amountPaid).isEqualTo(100_000); assertThat(july.status).isEqualTo(DueStatus.PAID); assertThat(august.amountPaid).isEqualTo(150_000); assertThat(august.status).isEqualTo(DueStatus.PARTIAL);
  }
  @Test void reversesPaymentAllocationsWithoutDeletingTheLedgerRecord() {
    DuePeriodDocument june = due("june", "2026-06", 100_000, 100_000); june.payments.add(new PaymentAllocation("payment-1", 100_000, Instant.now(), "automatic"));
    ManualPaymentDocument payment = new ManualPaymentDocument(); payment.id = "payment-1"; payment.memberId = "member-1"; payment.amount = 100_000; payment.allocations.add(new ManualPaymentDocument.ManualAllocation("june", 100_000));
    when(payments.findById("payment-1")).thenReturn(Optional.of(payment)); when(dues.findById("june")).thenReturn(Optional.of(june)); when(intents.findByMemberId("member-1")).thenReturn(List.of());
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    ApiDtos.PaymentReversalResponse result = service.reverse("payment-1", "admin", "Test payment rollback");

    assertThat(result.amount()).isEqualTo(100_000); assertThat(payment.reversed).isTrue(); assertThat(payment.reversalReason).isEqualTo("Test payment rollback"); assertThat(june.amountPaid).isZero(); assertThat(june.status).isEqualTo(DueStatus.UNPAID); assertThat(june.payments).isEmpty();
  }
  @Test void intentAllocationDoesNotFallBackToOlderMemberPeriods() {
    DuePeriodDocument july = due("july", "2026-07", 100_000, 0);
    DuePeriodDocument september = due("september", "2026-09", 100_000, 0);
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.id = "intent-1"; intent.memberId = "member-1"; intent.totalAmount = 100_000; intent.duePeriodIds = List.of("september"); intent.status = IntentStatus.PENDING;
    when(members.get("member-1")).thenReturn(member); when(payments.findByIdempotencyKey("gmail:provider-1")).thenReturn(Optional.empty()); when(payments.insert(org.mockito.ArgumentMatchers.<ManualPaymentDocument>any())).thenAnswer(invocation -> invocation.getArgument(0)); when(dues.findByMemberIdAndIdIn("member-1", java.util.Set.of("september"))).thenReturn(List.of(september)); when(intents.findByMemberId("member-1")).thenReturn(List.of(intent));
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    ApiDtos.ManualPaymentResponse result = service.recordForIntent(intent, 100_000, Instant.parse("2026-09-10T00:00:00Z"), "gmail:provider-1", "system:gmail-auto");

    assertThat(result.unallocatedAmount()).isZero(); assertThat(result.allocations()).extracting(ApiDtos.AllocationResponse::duePeriodId).containsExactly("september"); assertThat(july.amountPaid).isZero(); assertThat(september.amountPaid).isEqualTo(100_000); assertThat(september.status).isEqualTo(DueStatus.PAID);
  }
  private DuePeriodDocument due(String id, String month, long due, long paid) { DuePeriodDocument value = new DuePeriodDocument(); value.id = id; value.month = month; value.amountDue = due; value.amountPaid = paid; value.status = DueStatus.UNPAID; return value; }
}
