package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ContributionPeriod;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
  private final ManualPaymentRepository payments; private final DuePeriodRepository dues; private final PaymentIntentRepository intents; private final MemberService members; private final AuditService audit;
  public PaymentService(ManualPaymentRepository payments, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService members, AuditService audit) { this.payments = payments; this.dues = dues; this.intents = intents; this.members = members; this.audit = audit; }
  /** A durable record is inserted before allocation. Retrying the same key resumes safely after a crash. Production should use a Mongo replica-set transaction. */
  public ApiDtos.ManualPaymentResponse record(ApiDtos.ManualPaymentRequest request, String actor) {
    MemberDocument member = members.get(request.memberId()); ManualPaymentDocument payment = payments.findByIdempotencyKey(request.idempotencyKey()).orElseGet(() -> createPayment(request, actor));
    if (payment.reversed) throw new IllegalStateException("Payment has been reversed");
    if (!payment.memberId.equals(member.id) || payment.amount != request.amount()) throw new IllegalStateException("Idempotency key was already used for a different payment");
    applyOutstanding(payment, null); updateIntentStatuses(member.id); payment.recordedBy = actor; payments.save(payment); audit.record(actor, "MANUAL_PAYMENT_RECORDED", "manual_payment", payment.id, Map.of(), Map.of("memberId", member.id, "amount", payment.amount, "unallocatedAmount", payment.unallocatedAmount)); return toResponse(payment);
  }
  /** Automatic bank reconciliation path. Annual references constrain allocation to that calendar year. */
  public ApiDtos.ManualPaymentResponse recordAutomatic(String memberId, long amount, Instant occurredAt, String idempotencyKey, Integer year, String actor) {
    MemberDocument member = members.get(memberId);
    ApiDtos.ManualPaymentRequest request = new ApiDtos.ManualPaymentRequest(member.id, amount, occurredAt, idempotencyKey, "Automatic match from annual payment reference");
    ManualPaymentDocument payment = payments.findByIdempotencyKey(request.idempotencyKey()).orElseGet(() -> createPayment(request, actor));
    if (payment.reversed) throw new IllegalStateException("Payment has been reversed");
    if (!payment.memberId.equals(member.id) || payment.amount != amount) throw new IllegalStateException("Idempotency key was already used for a different payment");
    applyOutstanding(payment, year); updateIntentStatuses(member.id); payment.recordedBy = actor; payments.save(payment);
    if (audit != null) audit.record(actor, "BANK_PAYMENT_AUTO_ALLOCATED", "manual_payment", payment.id, Map.of(), Map.of("memberId", member.id, "amount", payment.amount, "allocated", payment.amount - payment.unallocatedAmount, "credit", payment.unallocatedAmount));
    return toResponse(payment);
  }
  /**
   * Automatic reconciliation for a payment intent. An intent is an explicit
   * user selection, so allocation must never fall back to member-wide FIFO.
   * The idempotency key is still the provider transaction id and a retry
   * resumes any allocation that was persisted before a process interruption.
   */
  public ApiDtos.ManualPaymentResponse recordForIntent(PaymentIntentDocument intent, long amount, Instant occurredAt, String idempotencyKey, String actor) {
    if (intent == null || intent.memberId == null || intent.duePeriodIds == null || intent.duePeriodIds.isEmpty()) {
      throw new IllegalArgumentException("Payment intent selection is invalid");
    }
    if (intent.totalAmount != amount) throw new IllegalArgumentException("Payment amount does not match intent");
    MemberDocument member = members.get(intent.memberId);
    ApiDtos.ManualPaymentRequest request = new ApiDtos.ManualPaymentRequest(member.id, amount, occurredAt, idempotencyKey, "Automatic match from payment intent");
    ManualPaymentDocument payment = payments.findByIdempotencyKey(idempotencyKey).orElseGet(() -> createPayment(request, actor));
    if (payment.reversed) throw new IllegalStateException("Payment has been reversed");
    if (!payment.memberId.equals(member.id) || payment.amount != amount) throw new IllegalStateException("Idempotency key was already used for a different payment");
    payment.allocationMode = "INTENT";
    applySelected(payment, intent.duePeriodIds);
    if (payment.unallocatedAmount != 0) throw new IllegalStateException("Intent allocation does not cover the payment amount");
    updateIntentStatuses(member.id);
    payment.recordedBy = actor; payments.save(payment);
    if (audit != null) audit.record(actor, "BANK_PAYMENT_AUTO_ALLOCATED", "manual_payment", payment.id, Map.of(), Map.of("memberId", member.id, "amount", payment.amount, "intentId", intent.id));
    return toResponse(payment);
  }
  /** Reverses a payment without deleting the original ledger record or its audit trail. */
  public ApiDtos.PaymentReversalResponse reverse(String paymentId, String actor, String reason) {
    ManualPaymentDocument payment = payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    if (payment.reversed) throw new IllegalStateException("Payment has already been reversed");
    List<ManualPaymentDocument.ManualAllocation> allocations = payment.allocations == null ? List.of() : List.copyOf(payment.allocations);
    List<ReversalAllocation> reversals = new ArrayList<>();
    for (ManualPaymentDocument.ManualAllocation allocation : allocations) {
      DuePeriodDocument due = dues.findById(allocation.duePeriodId).orElseThrow(() -> new IllegalStateException("Payment allocation due period not found"));
      List<PaymentAllocation> matched = due.payments == null ? List.of() : due.payments.stream().filter(item -> payment.id.equals(item.paymentId)).toList();
      long allocated = matched.stream().mapToLong(item -> item.amount).sum();
      if (allocated != allocation.amount) throw new IllegalStateException("Payment allocation changed; reversal aborted");
      if (due.amountPaid < allocated) throw new IllegalStateException("Due period balance is inconsistent; reversal aborted");
      reversals.add(new ReversalAllocation(due, allocated));
    }
    // Validate every allocation before mutating any due period, so a bad record
    // cannot leave the payment only partially reversed.
    for (ReversalAllocation reversal : reversals) {
      DuePeriodDocument due = reversal.due();
      if (due.payments == null) due.payments = new ArrayList<>();
      due.payments.removeIf(item -> payment.id.equals(item.paymentId));
      due.amountPaid -= reversal.amount(); due.status = status(due); dues.save(due);
    }
    payment.reversed = true; payment.reversedAt = Instant.now(); payment.reversedBy = actor; payment.reversalReason = reason.trim(); payments.save(payment);
    updateIntentStatuses(payment.memberId);
    if (audit != null) audit.record(actor, "PAYMENT_REVERSED", "manual_payment", payment.id, Map.of("memberId", payment.memberId, "amount", payment.amount), Map.of("memberId", payment.memberId, "amount", payment.amount, "reason", payment.reversalReason));
    return new ApiDtos.PaymentReversalResponse(payment.id, payment.memberId, payment.amount, payment.reversedAt, payment.reversalReason);
  }
  public ApiDtos.PaymentReversalResponse reverseByIdempotencyKey(String idempotencyKey, String actor, String reason) {
    ManualPaymentDocument payment = payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> new IllegalArgumentException("Payment for bank transaction not found"));
    return reverse(payment.id, actor, reason);
  }

  /**
   * Refreshes due snapshots from the member's effective rates and rebuilds only
   * member-wide FIFO allocations. Explicit QR intent allocations stay tied to
   * their selected periods. Payment documents remain append-only ledger records;
   * this operation changes allocation projections and records an audit event.
   */
  public void reallocateMemberPayments(MemberDocument member, String actor, String reason) {
    List<DuePeriodDocument> periods = dues.findByMemberIdOrderByMonthAsc(member.id).stream()
        .sorted(Comparator.comparing(due -> due.month == null ? "" : due.month,
            ContributionPeriod.comparator()))
        .toList();
    List<ManualPaymentDocument> allPayments = payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc(member.id);
    if (allPayments == null) return;
    List<ManualPaymentDocument> activePayments = allPayments.stream().filter(payment -> !payment.reversed)
        .sorted(Comparator.comparing((ManualPaymentDocument payment) -> payment.occurredAt,
            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(
                payment -> payment.createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
    List<ManualPaymentDocument> fifoPayments = activePayments.stream()
        .filter(payment -> !"INTENT".equals(payment.allocationMode)).toList();
    Set<String> fifoPaymentIds = fifoPayments.stream().map(payment -> payment.id)
        .collect(java.util.stream.Collectors.toSet());
    Set<String> intentPaymentIds = activePayments.stream().filter(payment -> "INTENT".equals(payment.allocationMode))
        .map(payment -> payment.id).collect(java.util.stream.Collectors.toSet());
    long unattributedPaid = 0;
    Map<String, String> beforeDue = new java.util.HashMap<>();
    Map<String, String> beforePayment = fifoPayments.stream()
        .collect(java.util.stream.Collectors.toMap(payment -> payment.id, this::paymentSummary, (first, second) -> first));

    for (DuePeriodDocument due : periods) {
      beforeDue.put(due.id, dueSummary(due));
      long fifoPaid = due.payments == null ? 0 : due.payments.stream()
          .filter(allocation -> fifoPaymentIds.contains(allocation.paymentId))
          .mapToLong(allocation -> allocation.amount).sum();
      long intentPaid = due.payments == null ? 0 : due.payments.stream()
          .filter(allocation -> intentPaymentIds.contains(allocation.paymentId))
          .mapToLong(allocation -> allocation.amount).sum();
      long unattributedForDue = Math.max(0, due.amountPaid - fifoPaid - intentPaid);
      if (due.notes == null || due.notes.isBlank()) {
        unattributedPaid = Math.addExact(unattributedPaid, unattributedForDue);
        due.amountPaid = intentPaid;
      } else {
        due.amountPaid = Math.max(0, due.amountPaid - fifoPaid);
      }
      if (due.notes == null || due.notes.isBlank()) {
        long expectedAmount = members.rateFor(member, due.month);
        due.amountDue = expectedAmount;
      }
      if (due.payments == null) due.payments = new ArrayList<>();
      due.payments.removeIf(allocation -> !intentPaymentIds.contains(allocation.paymentId));
      due.status = status(due);
    }
    for (ManualPaymentDocument payment : fifoPayments) {
      if (payment.allocations == null) payment.allocations = new ArrayList<>();
      payment.allocations.clear();
      payment.unallocatedAmount = payment.amount;
    }

    // Legacy imports and seeded/partial due records may carry paid money
    // without a corresponding manual_payment allocation. Treat that amount
    // as an anonymous paid pool so two 100k partials can become one 200k FIFO
    // payment after a rate boundary changes.
    if (unattributedPaid > 0) {
      allocateUnattributed(unattributedPaid, periods);
    }
    for (ManualPaymentDocument payment : fifoPayments) {
      long remaining = payment.amount;
      remaining = allocateFifo(payment, periods, remaining);
      payment.unallocatedAmount = remaining;
    }
    for (DuePeriodDocument due : periods) {
      due.status = status(due);
      dues.save(due);
      if (!dueSummary(due).equals(beforeDue.get(due.id)) && audit != null) {
        audit.record(actor == null ? "system" : actor, "DUE_PERIOD_REALLOCATED", "due_period", due.id,
            Map.of("before", beforeDue.get(due.id)), Map.of("after", dueSummary(due), "reason", reason == null ? "" : reason));
      }
    }
    for (ManualPaymentDocument payment : fifoPayments) {
      payments.save(payment);
      if (!paymentSummary(payment).equals(beforePayment.get(payment.id)) && audit != null) {
        audit.record(actor == null ? "system" : actor, "PAYMENT_REALLOCATED", "manual_payment", payment.id,
            Map.of("before", beforePayment.get(payment.id)),
            Map.of("after", paymentSummary(payment), "reason", reason == null ? "" : reason));
      }
    }
    updateIntentStatuses(member.id);
  }

  private long allocateFifo(ManualPaymentDocument payment, List<DuePeriodDocument> periods, long remaining) {
    for (DuePeriodDocument due : periods) {
      if (remaining == 0) break;
      long outstanding = Math.max(0, due.amountDue - due.amountPaid);
      if (outstanding == 0) continue;
      long allocated = Math.min(remaining, outstanding);
      due.amountPaid = Math.addExact(due.amountPaid, allocated);
      due.payments.add(new PaymentAllocation(payment.id, allocated, Instant.now(), "reallocation"));
      payment.allocations.add(new ManualPaymentDocument.ManualAllocation(due.id, allocated));
      remaining -= allocated;
    }
    return remaining;
  }

  private void allocateUnattributed(long remaining, List<DuePeriodDocument> periods) {
    for (DuePeriodDocument due : periods) {
      if (remaining == 0) break;
      long outstanding = Math.max(0, due.amountDue - due.amountPaid);
      if (outstanding == 0) continue;
      long allocated = Math.min(remaining, outstanding);
      due.amountPaid = Math.addExact(due.amountPaid, allocated);
      remaining -= allocated;
    }
    if (remaining > 0 && !periods.isEmpty()) {
      DuePeriodDocument last = periods.get(periods.size() - 1);
      last.amountPaid = Math.addExact(last.amountPaid, remaining);
    }
  }

  private String dueSummary(DuePeriodDocument due) {
    return due.amountDue + ":" + due.amountPaid + ":" + (due.payments == null ? 0 : due.payments.size()) + ":" + due.status;
  }

  private String paymentSummary(ManualPaymentDocument payment) {
    String allocations = payment.allocations == null ? "" : payment.allocations.stream()
        .map(allocation -> allocation.duePeriodId + "=" + allocation.amount)
        .collect(java.util.stream.Collectors.joining(","));
    return payment.amount + ":" + payment.unallocatedAmount + ":" + allocations;
  }
  private record ReversalAllocation(DuePeriodDocument due, long amount) {}
  private ManualPaymentDocument createPayment(ApiDtos.ManualPaymentRequest request, String actor) {
    ManualPaymentDocument payment = new ManualPaymentDocument(); payment.id = UUID.randomUUID().toString(); payment.memberId = request.memberId(); payment.idempotencyKey = request.idempotencyKey(); payment.amount = request.amount(); payment.unallocatedAmount = request.amount(); payment.occurredAt = request.occurredAt(); payment.note = request.note(); payment.recordedBy = actor; payment.createdAt = Instant.now();
    try { return payments.insert(payment); } catch (DuplicateKeyException ex) { return payments.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> ex); }
  }
  private void applyOutstanding(ManualPaymentDocument payment, Integer year) {
    long alreadyAllocated = payment.allocations.stream().mapToLong(a -> a.amount).sum(); long remaining = Math.subtractExact(payment.amount, alreadyAllocated); if (remaining == 0) { payment.unallocatedAmount = 0; return; }
    List<DuePeriodDocument> periods = dues.findByMemberIdOrderByMonthAsc(payment.memberId).stream().sorted(Comparator.comparing(p -> p.month)).toList(); List<ManualPaymentDocument.ManualAllocation> newAllocations = new ArrayList<>();
    for (DuePeriodDocument due : periods) { if (remaining == 0) break; if (year != null && (due.month == null || !due.month.startsWith(year + "-"))) continue; long outstanding = Math.max(0, due.amountDue - due.amountPaid); if (outstanding == 0) continue; long allocated = Math.min(remaining, outstanding); due.amountPaid = Math.addExact(due.amountPaid, allocated); due.status = status(due); due.payments.add(new PaymentAllocation(payment.id, allocated, Instant.now(), year == null ? "manual" : "automatic")); dues.save(due); newAllocations.add(new ManualPaymentDocument.ManualAllocation(due.id, allocated)); remaining -= allocated; }
    payment.allocations.addAll(newAllocations); payment.unallocatedAmount = remaining;
  }
  private void applySelected(ManualPaymentDocument payment, List<String> duePeriodIds) {
    long alreadyAllocated = payment.allocations.stream().mapToLong(a -> a.amount).sum();
    long remaining = Math.subtractExact(payment.amount, alreadyAllocated);
    if (remaining == 0) { payment.unallocatedAmount = 0; return; }
    List<DuePeriodDocument> periods = dues.findByMemberIdAndIdIn(payment.memberId, new java.util.HashSet<>(duePeriodIds)).stream()
        .sorted(Comparator.comparing(p -> p.month == null ? "" : p.month)).toList();
    if (periods.size() != new java.util.HashSet<>(duePeriodIds).size()) throw new IllegalArgumentException("Intent contains a due period for another member");
    long available = periods.stream().mapToLong(due -> Math.max(0, due.amountDue - due.amountPaid)).sum();
    if (available < remaining) throw new IllegalStateException("Selected due periods cannot cover the payment amount");
    List<ManualPaymentDocument.ManualAllocation> newAllocations = new ArrayList<>();
    for (DuePeriodDocument due : periods) {
      if (remaining == 0) break;
      long outstanding = Math.max(0, due.amountDue - due.amountPaid);
      if (outstanding == 0) continue;
      long allocated = Math.min(remaining, outstanding);
      due.amountPaid = Math.addExact(due.amountPaid, allocated); due.status = status(due);
      if (due.payments == null) due.payments = new ArrayList<>();
      due.payments.add(new PaymentAllocation(payment.id, allocated, Instant.now(), "intent")); dues.save(due);
      newAllocations.add(new ManualPaymentDocument.ManualAllocation(due.id, allocated)); remaining -= allocated;
    }
    payment.allocations.addAll(newAllocations); payment.unallocatedAmount = remaining;
  }
  private DueStatus status(DuePeriodDocument due) { if (due.amountPaid == 0) return DueStatus.UNPAID; if (due.amountPaid < due.amountDue) return DueStatus.PARTIAL; if (due.amountPaid == due.amountDue) return DueStatus.PAID; return DueStatus.OVERPAID; }
  private void updateIntentStatuses(String memberId) { for (PaymentIntentDocument intent : intents.findByMemberId(memberId)) { if (intent.status == IntentStatus.EXPIRED || intent.status == IntentStatus.CREDIT) continue; List<DuePeriodDocument> selected = dues.findAllById(intent.duePeriodIds); if (selected.size() != intent.duePeriodIds.size()) continue; boolean allPaid = selected.stream().allMatch(due -> due.amountPaid >= due.amountDue); boolean anyPaid = selected.stream().anyMatch(due -> due.amountPaid > 0); IntentStatus next = allPaid ? IntentStatus.FULFILLED : anyPaid ? IntentStatus.PARTIAL : IntentStatus.PENDING; if (intent.status != next) { intent.status = next; intents.save(intent); } } }
  private ApiDtos.ManualPaymentResponse toResponse(ManualPaymentDocument payment) { return new ApiDtos.ManualPaymentResponse(payment.id, payment.memberId, payment.amount, payment.unallocatedAmount, payment.allocations.stream().map(a -> new ApiDtos.AllocationResponse(a.duePeriodId, a.amount)).toList()); }
}
