package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
  private final BankTransactionRepository transactions; private final IngestionService ingestion; private final PaymentService payments; private final AuditService audit; private final MemberService members; private final NotificationService notifications;
  public ReconciliationService(BankTransactionRepository transactions, IngestionService ingestion, PaymentService payments, AuditService audit, MemberService members, NotificationService notifications) { this.transactions = transactions; this.ingestion = ingestion; this.payments = payments; this.audit = audit; this.members = members; this.notifications = notifications; }
  public ApiDtos.BankTransactionResponse review(String id, ApiDtos.ReconciliationReviewRequest request, String actor) {
    BankTransactionDocument tx = transactions.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank transaction not found"));
    if (Boolean.TRUE.equals(request.ignore())) { tx.reconciliationStatus = "IGNORED"; tx.reviewedBy = actor; tx.reviewedAt = Instant.now(); transactions.save(tx); audit.record(actor, "BANK_TRANSACTION_IGNORED", "bank_transaction", id, Map.of("status", "NEEDS_REVIEW"), Map.of("status", "IGNORED")); return ingestion.response(tx); }
    if (!"IN".equals(tx.direction) || request.memberId() == null || request.memberId().isBlank()) throw new IllegalArgumentException("An incoming transaction and member are required");
    String key = "bank:" + tx.providerMessageId;
    var payment = payments.record(new ApiDtos.ManualPaymentRequest(request.memberId(), tx.amount, tx.occurredAt, key, request.note()), actor);
    tx.reconciliationStatus = "MATCHED"; tx.matchedMemberId = request.memberId(); tx.reviewedBy = actor; tx.reviewedAt = Instant.now(); tx.matchedDuePeriodIds = payment.allocations().stream().map(ApiDtos.AllocationResponse::duePeriodId).toList(); transactions.save(tx);
    audit.record(actor, "BANK_TRANSACTION_MATCHED", "bank_transaction", id, Map.of("status", "NEEDS_REVIEW"), Map.of("status", "MATCHED", "memberId", request.memberId()));
    var member = members.get(request.memberId());
    if (member.email != null && !member.email.isBlank()) notifications.send(member.id, "confirmation", new NotificationSender.NotificationMessage(member.email, "Fund payment recorded", "Your fund payment has been recorded.", "confirmation:" + tx.providerMessageId));
    return ingestion.response(tx);
  }
}
