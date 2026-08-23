package com.fundmanagement.api.service;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    if (!payment.memberId.equals(member.id) || payment.amount != request.amount()) throw new IllegalStateException("Idempotency key was already used for a different payment");
    applyOutstanding(payment); fulfillEligibleIntents(member.id); payment.recordedBy = actor; payments.save(payment); audit.record(actor, "MANUAL_PAYMENT_RECORDED", "manual_payment", payment.id, Map.of(), Map.of("amount", payment.amount, "unallocatedAmount", payment.unallocatedAmount)); return toResponse(payment);
  }
  private ManualPaymentDocument createPayment(ApiDtos.ManualPaymentRequest request, String actor) {
    ManualPaymentDocument payment = new ManualPaymentDocument(); payment.id = UUID.randomUUID().toString(); payment.memberId = request.memberId(); payment.idempotencyKey = request.idempotencyKey(); payment.amount = request.amount(); payment.unallocatedAmount = request.amount(); payment.occurredAt = request.occurredAt(); payment.note = request.note(); payment.recordedBy = actor; payment.createdAt = Instant.now();
    try { return payments.insert(payment); } catch (DuplicateKeyException ex) { return payments.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> ex); }
  }
  private void applyOutstanding(ManualPaymentDocument payment) {
    long alreadyAllocated = payment.allocations.stream().mapToLong(a -> a.amount).sum(); long remaining = Math.subtractExact(payment.amount, alreadyAllocated); if (remaining == 0) { payment.unallocatedAmount = 0; return; }
    List<DuePeriodDocument> periods = dues.findByMemberIdOrderByMonthAsc(payment.memberId).stream().sorted(Comparator.comparing(p -> p.month)).toList(); List<ManualPaymentDocument.ManualAllocation> newAllocations = new ArrayList<>();
    for (DuePeriodDocument due : periods) { if (remaining == 0) break; long outstanding = Math.max(0, due.amountDue - due.amountPaid); if (outstanding == 0) continue; long allocated = Math.min(remaining, outstanding); due.amountPaid = Math.addExact(due.amountPaid, allocated); due.status = status(due); due.payments.add(new PaymentAllocation(payment.id, allocated, Instant.now(), "manual")); dues.save(due); newAllocations.add(new ManualPaymentDocument.ManualAllocation(due.id, allocated)); remaining -= allocated; }
    payment.allocations.addAll(newAllocations); payment.unallocatedAmount = remaining;
  }
  private DueStatus status(DuePeriodDocument due) { if (due.amountPaid == 0) return DueStatus.UNPAID; if (due.amountPaid < due.amountDue) return DueStatus.PARTIAL; if (due.amountPaid == due.amountDue) return DueStatus.PAID; return DueStatus.OVERPAID; }
  private void fulfillEligibleIntents(String memberId) { for (PaymentIntentDocument intent : intents.findByMemberId(memberId)) { if (intent.status != IntentStatus.PENDING) continue; List<DuePeriodDocument> selected = dues.findAllById(intent.duePeriodIds); if (selected.size() == intent.duePeriodIds.size() && selected.stream().allMatch(due -> due.amountPaid >= due.amountDue)) { intent.status = IntentStatus.FULFILLED; intents.save(intent); } } }
  private ApiDtos.ManualPaymentResponse toResponse(ManualPaymentDocument payment) { return new ApiDtos.ManualPaymentResponse(payment.id, payment.memberId, payment.amount, payment.unallocatedAmount, payment.allocations.stream().map(a -> new ApiDtos.AllocationResponse(a.duePeriodId, a.amount)).toList()); }
}
