package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import com.fundmanagement.api.repository.PaymentReferenceRepository;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {
  private final BankTransactionRepository transactions; private final SacombankMessageParser parser; private final SettingsService settings; private final PaymentIntentRepository intents; private final PaymentService payments; private final AuditService audit; private final GmailProperties gmail; private final MongoTemplate mongo; private final PaymentReferenceRepository references;
  public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings) { this(transactions, parser, settings, null, null, null, null, null, null); }
  /** Compatibility constructor retained for focused tests that predate annual references. */
  public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings, PaymentIntentRepository intents, PaymentService payments, AuditService audit, GmailProperties gmail, MongoTemplate mongo) { this(transactions, parser, settings, intents, payments, audit, gmail, mongo, null); }
  @Autowired public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings, PaymentIntentRepository intents, PaymentService payments, AuditService audit, GmailProperties gmail, MongoTemplate mongo, PaymentReferenceRepository references) { this.transactions = transactions; this.parser = parser; this.settings = settings; this.intents = intents; this.payments = payments; this.audit = audit; this.gmail = gmail; this.mongo = mongo; this.references = references; }
  public ApiDtos.BankTransactionResponse ingest(ApiDtos.BankTransactionIngestionRequest request) {
    if (!settings.ingestionStatus().enabled()) throw new IllegalStateException("Bank ingestion is disabled");
    var existing = transactions.findByProviderMessageId(request.providerMessageId()); if (existing.isPresent()) {
      BankTransactionDocument current = existing.get();
      if (!"MATCHED".equals(current.reconciliationStatus) && !"IGNORED".equals(current.reconciliationStatus)) reparse(current);
      tryAutoMatch(current); return response(current);
    }
    BankTransactionDocument tx = new BankTransactionDocument(); tx.providerMessageId = request.providerMessageId().trim(); tx.rawPayload = request.rawPayload(); tx.createdAt = Instant.now(); tx.reconciliationStatus = "PARSE_FAILED";
    try {
      applyParsed(tx, parser.parse(request.rawPayload()));
    } catch (IllegalArgumentException ex) {
      if ("Bank message was not for the configured receiving account".equals(ex.getMessage())) throw ex;
      tx.occurredAt = tx.createdAt;
    }
    try { BankTransactionDocument saved = transactions.insert(tx); tryAutoMatch(saved); return response(saved); }
    catch (DuplicateKeyException duplicate) { BankTransactionDocument duplicateRecord = transactions.findByProviderMessageId(request.providerMessageId()).orElseThrow(() -> duplicate); tryAutoMatch(duplicateRecord); return response(duplicateRecord); }
  }
  private boolean sameAccount(String actual, String expected) { String left = normalizeDigits(actual); String right = normalizeDigits(expected); return left != null && right != null && !right.isBlank() && right.equals(left); }
  private void reparse(BankTransactionDocument tx) {
    try { applyParsed(tx, parser.parse(tx.rawPayload)); transactions.save(tx); }
    catch (IllegalArgumentException ignored) { /* Keep the existing record for admin review. */ }
  }
  private void applyParsed(BankTransactionDocument tx, SacombankMessageParser.ParsedBankMessage parsed) {
    if (!sameAccount(parsed.accountNumber(), settings.get().accountNumber())) throw new IllegalArgumentException("Bank message was not for the configured receiving account");
    tx.occurredAt = parsed.occurredAt(); tx.amount = parsed.amount(); tx.direction = parsed.direction(); tx.accountNumber = normalizeDigits(parsed.accountNumber()); tx.parsedContent = parsed.parsedContent(); tx.availableBalance = parsed.availableBalance(); tx.reconciliationStatus = "NEEDS_REVIEW";
  }
  private String normalizeDigits(String value) { if (value == null) return null; String result = value.replaceAll("\\D", ""); return result.isBlank() ? null : result; }

  /** Claims a transaction atomically before matching, so two replicas cannot allocate it twice. */
  private void tryAutoMatch(BankTransactionDocument candidate) {
    if (payments == null || intents == null || mongo == null || candidate == null || candidate.id == null || candidate.amount <= 0 || !"IN".equals(candidate.direction) || !"NEEDS_REVIEW".equals(candidate.reconciliationStatus)) return;
    Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(candidate.id), Criteria.where("reconciliationStatus").is("NEEDS_REVIEW")));
    Update claim = new Update().set("reconciliationStatus", "AUTO_MATCHING");
    BankTransactionDocument claimed = mongo.findAndModify(query, claim, FindAndModifyOptions.options().returnNew(true), BankTransactionDocument.class);
    if (claimed == null) return;
    try {
      List<PaymentIntentDocument> matches = intents.findByStatus(IntentStatus.PENDING).stream().filter(i -> exactIntentMatch(i, claimed)).toList();
      PaymentIntentDocument intent = matches.size() == 1 ? matches.getFirst() : null;
      Integer annualYear = null;
      if (intent == null && references != null) {
        // Payment references are opaque 10-character tokens. Extracting tokens
        // from the parsed description and looking them up by the unique code
        // index avoids scanning the entire reference collection on every mail.
        var annualMatches = java.util.regex.Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{10}(?![A-Z0-9])")
            .matcher(claimed.parsedContent == null ? "" : claimed.parsedContent.toUpperCase(java.util.Locale.ROOT));
        java.util.Map<String, com.fundmanagement.api.domain.PaymentReferenceDocument> found = new java.util.HashMap<>();
        while (annualMatches.find()) references.findByCode(annualMatches.group()).filter(ref -> ref.year != null).ifPresent(ref -> found.put(ref.code, ref));
        if (found.size() == 1) { var annual = found.values().iterator().next(); intent = new PaymentIntentDocument(); intent.memberId = annual.memberId; annualYear = annual.year; }
      }
      if (intent == null) { claimed.reconciliationStatus = "NEEDS_REVIEW"; transactions.save(claimed); return; }
      ApiDtos.ManualPaymentResponse payment = annualYear == null
          ? payments.record(new ApiDtos.ManualPaymentRequest(intent.memberId, claimed.amount, claimed.occurredAt, "gmail:" + claimed.providerMessageId, "Automatic match from configured read-only bank alert"), "system:gmail-auto")
          : payments.recordAutomatic(intent.memberId, claimed.amount, claimed.occurredAt, "gmail:" + claimed.providerMessageId, annualYear, "system:gmail-auto");
      claimed.reconciliationStatus = "MATCHED"; claimed.matchedMemberId = intent.memberId; claimed.matchedDuePeriodIds = payment.allocations().stream().map(ApiDtos.AllocationResponse::duePeriodId).toList(); claimed.reviewedBy = "system:gmail-auto"; claimed.reviewedAt = Instant.now(); transactions.save(claimed);
      if (audit != null) audit.record("system:gmail-auto", "BANK_TRANSACTION_AUTO_MATCHED", "bank_transaction", claimed.id, java.util.Map.of("status", "NEEDS_REVIEW"), java.util.Map.of("status", "MATCHED", "amount", claimed.amount));
    } catch (RuntimeException failure) { claimed.reconciliationStatus = "NEEDS_REVIEW"; transactions.save(claimed); }
  }
  private boolean exactIntentMatch(PaymentIntentDocument intent, BankTransactionDocument tx) {
    if (intent == null || intent.status != IntentStatus.PENDING || intent.totalAmount != tx.amount || intent.transferContent == null || intent.transferContent.isBlank() || !transferMatches(intent.transferContent, tx.parsedContent) || intent.createdAt == null || tx.occurredAt == null) return false;
    long window = gmail == null ? 300 : Math.max(1, gmail.getIntentMatchSeconds());
    Duration age = Duration.between(intent.createdAt, tx.occurredAt);
    return !age.isNegative() && age.getSeconds() <= window && (intent.expiresAt == null || !tx.occurredAt.isAfter(intent.expiresAt));
  }
  private boolean transferMatches(String expected, String actual) {
    if (actual == null) return false;
    if (!expected.matches("[A-Z0-9]{10}")) return expected.equals(actual);
    String content = actual.toUpperCase(java.util.Locale.ROOT);
    String code = expected.toUpperCase(java.util.Locale.ROOT);
    return java.util.regex.Pattern.compile("(?:^|[^A-Z0-9])" + java.util.regex.Pattern.quote(code) + "(?:$|[^A-Z0-9])").matcher(content).find();
  }
  public ApiDtos.ReconciliationPageResponse reviewQueue(int page, int size) { var result = transactions.findByReconciliationStatusOrderByOccurredAtDesc("NEEDS_REVIEW", org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))); return new ApiDtos.ReconciliationPageResponse(result.getContent().stream().map(this::response).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }
  public ApiDtos.BankTransactionResponse response(BankTransactionDocument tx) { return new ApiDtos.BankTransactionResponse(tx.id, tx.providerMessageId, tx.occurredAt, tx.amount, tx.direction, tx.parsedContent, tx.availableBalance, tx.reconciliationStatus, tx.matchedMemberId, tx.matchedDuePeriodIds, tx.createdAt); }
}
