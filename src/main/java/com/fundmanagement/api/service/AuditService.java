package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.AuditLogDocument;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.fundmanagement.api.repository.AuditLogRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import com.fundmanagement.api.repository.MemberRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditLogRepository repository; private final BankTransactionRepository transactions; private final ManualPaymentRepository payments; private final MemberRepository members;
  public AuditService(AuditLogRepository repository, BankTransactionRepository transactions, ManualPaymentRepository payments, MemberRepository members) { this.repository = repository; this.transactions = transactions; this.payments = payments; this.members = members; }
  public void record(String actor, String action, String entityType, String entityId, Map<String, Object> before, Map<String, Object> after) {
    AuditLogDocument event = new AuditLogDocument(); event.actor = actor; event.action = action; event.entityType = entityType; event.entityId = entityId; event.before = before; event.after = after; event.timestamp = Instant.now(); repository.save(event);
  }
  public com.fundmanagement.api.dto.ApiDtos.AuditLogPageResponse list(int page, int size, String entityType) {
    if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page or size");
    var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("timestamp").descending());
    var result = entityType == null || entityType.isBlank() ? repository.findAllByOrderByTimestampDesc(pageable) : repository.findByEntityTypeOrderByTimestampDesc(entityType, pageable);
    return new com.fundmanagement.api.dto.ApiDtos.AuditLogPageResponse(result.getContent().stream().map(e -> new com.fundmanagement.api.dto.ApiDtos.AuditLogResponse(e.id, e.actor, e.action, e.entityType, e.entityId, e.before, enrichAfter(e), e.timestamp)).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
  }
  /** Adds safe member context to payment audit rows without exposing contacts or raw bank payloads. */
  private Map<String, Object> enrichAfter(AuditLogDocument event) {
    Map<String, Object> after = new HashMap<>(event.after == null ? Map.of() : event.after);
    String memberId = value(after.get("memberId"));
    if (memberId == null && "bank_transaction".equals(event.entityType) && event.entityId != null) memberId = transactions.findById(event.entityId).map(tx -> tx.matchedMemberId).orElse(null);
    if (memberId == null && "manual_payment".equals(event.entityType) && event.entityId != null) memberId = payments.findById(event.entityId).map(payment -> payment.memberId).orElse(null);
    if (memberId != null) {
      after.putIfAbsent("memberId", memberId);
      final String resolvedMemberId = memberId;
      members.findById(resolvedMemberId).ifPresent(member -> { after.putIfAbsent("memberCode", member.memberCode); after.putIfAbsent("memberName", member.fullName); });
    }
    return after;
  }
  private String value(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}
