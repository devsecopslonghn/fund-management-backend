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
  private FinancialTransactions financialTransactions;
  @org.springframework.beans.factory.annotation.Autowired
  public void setFinancialTransactions(FinancialTransactions transactions) { this.financialTransactions = transactions; }
  public ApiDtos.BankTransactionResponse review(String id, ApiDtos.ReconciliationReviewRequest request, String actor) {
    ReviewOutcome outcome = financialTransactions == null ? reviewInternal(id, request, actor)
        : financialTransactions.run(() -> reviewInternal(id, request, actor));
    // External delivery must happen only after a successful financial commit.
    if (outcome.notifyMemberId() != null) {
      var member = members.get(outcome.notifyMemberId());
      if (member.email != null && !member.email.isBlank()) notifications.send(member.id, "confirmation", new NotificationSender.NotificationMessage(member.email, "Fund payment recorded", "Your fund payment has been recorded.", "confirmation:" + outcome.providerMessageId()));
    }
    return outcome.response();
  }
  private ReviewOutcome reviewInternal(String id, ApiDtos.ReconciliationReviewRequest request, String actor) {
    BankTransactionDocument tx = transactions.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank transaction not found"));
    if ("MATCHED".equals(tx.reconciliationStatus) && Boolean.FALSE.equals(request.ignore())
        && tx.matchedMemberId != null && tx.matchedMemberId.equals(request.memberId())) {
      // Normalize old manual-review labels without recording or reallocating money.
      if ((tx.matchMethod == null || "MANUAL".equals(tx.matchMethod))
          && (tx.matchMethod == null || tx.conflictReason != null
              || (tx.matchCandidates != null && !tx.matchCandidates.isEmpty()))) {
        Map<String, Object> before = Map.of("matchMethod", tx.matchMethod == null ? "" : tx.matchMethod,
            "conflictReason", tx.conflictReason == null ? "" : tx.conflictReason);
        tx.matchMethod = "MANUAL"; tx.conflictReason = null; tx.matchCandidates = java.util.List.of();
        transactions.save(tx);
        audit.record(actor, "BANK_TRANSACTION_REVIEW_METADATA_CORRECTED", "bank_transaction", id,
            before, Map.of("matchMethod", "MANUAL", "memberId", tx.matchedMemberId));
      }
      return new ReviewOutcome(ingestion.response(tx), null, null);
    }
    if (!"NEEDS_REVIEW".equals(tx.reconciliationStatus)) throw new IllegalStateException("Only a transaction awaiting review can be matched or ignored");
    if (Boolean.TRUE.equals(request.ignore())) { tx.reconciliationStatus = "IGNORED"; tx.reviewedBy = actor; tx.reviewedAt = Instant.now(); transactions.save(tx); audit.record(actor, "BANK_TRANSACTION_IGNORED", "bank_transaction", id, Map.of("status", "NEEDS_REVIEW"), Map.of("status", "IGNORED")); return new ReviewOutcome(ingestion.response(tx), null, null); }
    if (!"IN".equals(tx.direction) || request.memberId() == null || request.memberId().isBlank()) throw new IllegalArgumentException("An incoming transaction and member are required");
    String key = "bank:" + tx.providerMessageId;
    var payment = payments.record(new ApiDtos.ManualPaymentRequest(request.memberId(), tx.amount, tx.occurredAt, key, request.note()), actor);
    tx.reconciliationStatus = "MATCHED"; tx.matchedMemberId = request.memberId(); tx.reviewedBy = actor; tx.reviewedAt = Instant.now(); tx.matchedDuePeriodIds = payment.allocations().stream().map(ApiDtos.AllocationResponse::duePeriodId).toList();
    tx.matchMethod = "MANUAL"; tx.conflictReason = null; tx.matchCandidates = java.util.List.of();
    transactions.save(tx);
    audit.record(actor, "BANK_TRANSACTION_MATCHED", "bank_transaction", id, Map.of("status", "NEEDS_REVIEW"), Map.of("status", "MATCHED", "memberId", request.memberId()));
    return new ReviewOutcome(ingestion.response(tx), request.memberId(), tx.providerMessageId);
  }
  private record ReviewOutcome(ApiDtos.BankTransactionResponse response, String notifyMemberId, String providerMessageId) {}
  public ApiDtos.BankTransactionResponse restore(String id, ApiDtos.ReconciliationRestoreRequest request, String actor) {
    BankTransactionDocument tx = transactions.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank transaction not found"));
    if (!"IGNORED".equals(tx.reconciliationStatus)) throw new IllegalStateException("Only ignored transactions can be restored");
    if ((tx.matchedDuePeriodIds != null && !tx.matchedDuePeriodIds.isEmpty()) || (tx.matchedMemberId != null && !tx.matchedMemberId.isBlank())) throw new IllegalStateException("A transaction with payment allocation cannot be restored");
    tx.reconciliationStatus = "NEEDS_REVIEW";
    tx.reviewedBy = null;
    tx.reviewedAt = null;
    transactions.save(tx);
    audit.record(actor, "BANK_TRANSACTION_REOPENED", "bank_transaction", id, Map.of("status", "IGNORED"), Map.of("status", "NEEDS_REVIEW", "reason", request.reason()));
    return ingestion.response(tx);
  }
  public ApiDtos.BankTransactionResponse reverse(String id, ApiDtos.PaymentReversalRequest request, String actor) {
    BankTransactionDocument tx = transactions.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank transaction not found"));
    if (!"MATCHED".equals(tx.reconciliationStatus)) throw new IllegalStateException("Only a matched bank transaction can be reversed");
    String providerKey = tx.providerMessageId;
    try {
      payments.reverseByIdempotencyKey("gmail:" + providerKey, actor, request.reason());
    } catch (IllegalArgumentException missingGmailKey) {
      payments.reverseByIdempotencyKey("bank:" + providerKey, actor, request.reason());
    }
    String previousStatus = tx.reconciliationStatus;
    tx.reconciliationStatus = "REVERSED"; tx.reviewedBy = actor; tx.reviewedAt = Instant.now(); transactions.save(tx);
    audit.record(actor, "BANK_TRANSACTION_REVERSED", "bank_transaction", id, Map.of("status", previousStatus, "memberId", tx.matchedMemberId, "amount", tx.amount), Map.of("status", "REVERSED", "memberId", tx.matchedMemberId, "amount", tx.amount, "reason", request.reason()));
    return ingestion.response(tx);
  }
}
