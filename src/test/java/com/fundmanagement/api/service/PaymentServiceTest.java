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
import java.util.ArrayList;
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
  @Test void twoPartialPaymentsAccumulateAndCloseTheSameOutstandingPeriod() {
    DuePeriodDocument september = due("september", "2026-09", 100_000, 0);
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    when(members.get("member-1")).thenReturn(member);
    when(payments.findByIdempotencyKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    when(payments.insert(org.mockito.ArgumentMatchers.<ManualPaymentDocument>any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(List.of(september));
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    ApiDtos.ManualPaymentResponse first = service.record(new ApiDtos.ManualPaymentRequest("member-1", 40_000,
        Instant.parse("2026-09-01T00:00:00Z"), "partial-payment-key-01", "first partial"), "admin");
    ApiDtos.ManualPaymentResponse second = service.record(new ApiDtos.ManualPaymentRequest("member-1", 60_000,
        Instant.parse("2026-09-02T00:00:00Z"), "partial-payment-key-02", "second partial"), "admin");

    assertThat(first.unallocatedAmount()).isZero();
    assertThat(second.unallocatedAmount()).isZero();
    assertThat(september.amountPaid).isEqualTo(100_000);
    assertThat(september.status).isEqualTo(DueStatus.PAID);
    assertThat(september.payments).extracting(payment -> payment.amount).containsExactly(40_000L, 60_000L);
  }

  @Test void rateChangeIncidentKeepsHistoricalPartialOnItsOriginalPeriodBeforeFifoContinues() {
    // Regression fixture for the live THOTV17094 shape: the old 100k payment
    // belongs to August after its rate changed to 200k. A later 500k payment
    // therefore closes August first and leaves September at 400k/500k.
    DuePeriodDocument august = due("august", "2026-08", 200_000, 100_000);
    DuePeriodDocument september = due("september", "2026-09", 500_000, 0);
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    when(members.get("member-1")).thenReturn(member);
    when(payments.findByIdempotencyKey("gmail:rate-change-incident")).thenReturn(Optional.empty());
    when(payments.insert(org.mockito.ArgumentMatchers.<ManualPaymentDocument>any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(List.of(august, september));
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    ApiDtos.ManualPaymentResponse result = service.recordAutomatic("member-1", 500_000,
        Instant.parse("2026-09-09T11:13:00Z"), "gmail:rate-change-incident", 2026, "system:gmail-auto");

    assertThat(result.unallocatedAmount()).isZero();
    assertThat(result.allocations()).extracting(ApiDtos.AllocationResponse::duePeriodId)
        .containsExactly("august", "september");
    assertThat(august.amountPaid).isEqualTo(200_000);
    assertThat(august.status).isEqualTo(DueStatus.PAID);
    assertThat(september.amountPaid).isEqualTo(400_000);
    assertThat(september.status).isEqualTo(DueStatus.PARTIAL);
    assertThat(august.amountPaid + september.amountPaid).isEqualTo(600_000);
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
  @Test void reallocatesPaidAmountUsingEachMonthsEffectiveRate() {
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    List<DuePeriodDocument> periods = new ArrayList<>();
    ManualPaymentDocument payment = payment("payment-1", 1_400_000);
    for (int month = 1; month <= 12; month++) {
      DuePeriodDocument due = due("due-" + month, String.format("2026-%02d", month), 100_000, 100_000);
      due.payments.add(new PaymentAllocation(payment.id, 100_000, Instant.now(), "fifo"));
      payment.allocations.add(new ManualPaymentDocument.ManualAllocation(due.id, 100_000));
      periods.add(due);
    }
    payment.unallocatedAmount = 200_000;
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(periods);
    when(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc("member-1")).thenReturn(List.of(payment));
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    when(members.rateFor(org.mockito.ArgumentMatchers.same(member), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1, String.class).compareTo("2026-09") >= 0 ? 200_000L : 100_000L);
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    service.reallocateMemberPayments(member, "admin", "Promotion effective from September");

    assertThat(periods.subList(0, 8)).allMatch(due -> due.amountDue == 100_000 && due.amountPaid == 100_000);
    assertThat(periods.subList(8, 11)).allMatch(due -> due.amountDue == 200_000 && due.amountPaid == 200_000);
    assertThat(periods.get(11).amountDue).isEqualTo(200_000);
    assertThat(periods.get(11).amountPaid).isZero();
    assertThat(payment.unallocatedAmount).isZero();
    assertThat(payment.allocations).extracting(allocation -> allocation.duePeriodId)
        .containsExactly("due-1", "due-2", "due-3", "due-4", "due-5", "due-6", "due-7", "due-8", "due-9", "due-10", "due-11");
  }
  @Test void reallocatesPrepaidRemainderWhenNewDuePeriodsAreCreated() {
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    ManualPaymentDocument payment = payment("payment-1", 1_400_000);
    List<DuePeriodDocument> periods = new ArrayList<>();
    for (int month = 1; month <= 8; month++) periods.add(due("due-" + month, String.format("2026-%02d", month), 100_000, 0));
    when(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc("member-1")).thenReturn(List.of(payment));
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    when(members.rateFor(org.mockito.ArgumentMatchers.same(member), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1, String.class).compareTo("2026-09") >= 0 ? 200_000L : 100_000L);
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(periods);

    service.reallocateMemberPayments(member, "system", "Initial due generation");
    assertThat(payment.unallocatedAmount).isEqualTo(600_000);
    for (int month = 9; month <= 11; month++) periods.add(due("due-" + month, String.format("2026-%02d", month), 200_000, 0));

    service.reallocateMemberPayments(member, "system", "Later due generation");

    assertThat(periods).allMatch(due -> due.amountPaid == due.amountDue);
    assertThat(payment.unallocatedAmount).isZero();
  }
  @Test void combinesUnattributedPartialPaymentsIntoTheOldestOutstandingPeriod() {
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    List<DuePeriodDocument> periods = new ArrayList<>();
    for (int month = 1; month <= 10; month++) {
      long amountDue = month <= 8 ? 100_000 : 200_000;
      long amountPaid = month <= 8 ? 100_000 : 100_000;
      periods.add(due("due-" + month, String.format("2026-%02d", month), amountDue, amountPaid));
    }
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(periods);
    when(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc("member-1")).thenReturn(List.of());
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    when(members.rateFor(org.mockito.ArgumentMatchers.same(member), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1, String.class).compareTo("2026-09") >= 0 ? 200_000L : 100_000L);
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    service.reallocateMemberPayments(member, "system", "Normalize legacy partial allocations");

    assertThat(periods.get(8).amountPaid).isEqualTo(200_000);
    assertThat(periods.get(9).amountPaid).isZero();
  }
  @Test void seededPartialsAreConsolidatedEvenWhenTheImportedRowHasNotes() {
    MemberDocument member = new MemberDocument(); member.id = "member-1";
    DuePeriodDocument september = due("september", "2026-09", 200_000, 100_000);
    DuePeriodDocument october = due("october", "2026-10", 200_000, 100_000);
    september.seededPaid = true; october.seededPaid = true;
    september.notes = "Imported master plan"; october.notes = "Imported master plan";
    List<DuePeriodDocument> periods = new ArrayList<>(List.of(september, october));
    when(dues.findByMemberIdOrderByMonthAsc("member-1")).thenReturn(periods);
    when(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc("member-1")).thenReturn(List.of());
    when(intents.findByMemberId("member-1")).thenReturn(List.of());
    when(members.rateFor(org.mockito.ArgumentMatchers.same(member), org.mockito.ArgumentMatchers.anyString())).thenReturn(200_000L);
    PaymentService service = new PaymentService(payments, dues, intents, members, audit);

    service.reallocateMemberPayments(member, "system", "Normalize seeded plan payments");

    assertThat(september.amountPaid).isEqualTo(200_000);
    assertThat(september.status).isEqualTo(DueStatus.PAID);
    assertThat(october.amountPaid).isZero();
    assertThat(october.status).isEqualTo(DueStatus.UNPAID);
  }
  private ManualPaymentDocument payment(String id, long amount) {
    ManualPaymentDocument payment = new ManualPaymentDocument(); payment.id = id; payment.memberId = "member-1"; payment.amount = amount; payment.unallocatedAmount = amount; payment.occurredAt = Instant.parse("2026-01-01T00:00:00Z"); payment.createdAt = payment.occurredAt; return payment;
  }
  private DuePeriodDocument due(String id, String month, long due, long paid) { DuePeriodDocument value = new DuePeriodDocument(); value.id = id; value.month = month; value.amountDue = due; value.amountPaid = paid; value.status = DueStatus.UNPAID; return value; }
}
