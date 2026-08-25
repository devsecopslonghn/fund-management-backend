package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import com.fundmanagement.api.repository.PaymentReferenceRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {
  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
  private final BankTransactionRepository transactions; private final SacombankMessageParser parser; private final SettingsService settings; private final PaymentIntentRepository intents; private final PaymentService payments; private final AuditService audit; private final GmailProperties gmail; private final MongoTemplate mongo; private final PaymentReferenceRepository references; private final MemberRepository members;
  public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings) { this(transactions, parser, settings, null, null, null, null, null, null, null); }
  /** Compatibility constructor retained for focused tests that predate annual references. */
  public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings, PaymentIntentRepository intents, PaymentService payments, AuditService audit, GmailProperties gmail, MongoTemplate mongo) { this(transactions, parser, settings, intents, payments, audit, gmail, mongo, null, null); }
  @Autowired public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings, PaymentIntentRepository intents, PaymentService payments, AuditService audit, GmailProperties gmail, MongoTemplate mongo, PaymentReferenceRepository references, MemberRepository members) { this.transactions = transactions; this.parser = parser; this.settings = settings; this.intents = intents; this.payments = payments; this.audit = audit; this.gmail = gmail; this.mongo = mongo; this.references = references; this.members = members; }
  public ApiDtos.BankTransactionResponse ingest(ApiDtos.BankTransactionIngestionRequest request) {
    if (!settings.ingestionStatus().enabled()) throw new IllegalStateException("Bank ingestion is disabled");
    var existing = transactions.findByProviderMessageId(request.providerMessageId()); if (existing.isPresent()) {
      BankTransactionDocument current = existing.get();
      if (!"MATCHED".equals(current.reconciliationStatus) && !"IGNORED".equals(current.reconciliationStatus) && !"REVERSED".equals(current.reconciliationStatus)) reparse(current);
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
    if (!sameAccount(parsed.accountNumber(), settings.get().accountNumber())) {
      log.warn("Bank alert account mismatch actualPrefix={} actualSuffix={} actualDigits={} expectedPrefix={} expectedSuffix={} expectedDigits={}",
          accountPrefix(parsed.accountNumber()), accountSuffix(parsed.accountNumber()), digitCount(parsed.accountNumber()),
          accountPrefix(settings.get().accountNumber()), accountSuffix(settings.get().accountNumber()), digitCount(settings.get().accountNumber()));
      throw new IllegalArgumentException("Bank message was not for the configured receiving account");
    }
    tx.occurredAt = parsed.occurredAt(); tx.amount = parsed.amount(); tx.direction = parsed.direction(); tx.accountNumber = normalizeDigits(parsed.accountNumber()); tx.parsedContent = parsed.parsedContent(); tx.availableBalance = parsed.availableBalance(); tx.matchMethod = null; tx.conflictReason = null; tx.matchCandidates = new ArrayList<>(); tx.reconciliationStatus = "NEEDS_REVIEW";
  }
  private String normalizeDigits(String value) { if (value == null) return null; String result = value.replaceAll("\\D", ""); return result.isBlank() ? null : result; }
  private String accountSuffix(String value) { String digits = normalizeDigits(value); return digits == null ? "none" : digits.substring(Math.max(0, digits.length() - 4)); }
  private String accountPrefix(String value) { String digits = normalizeDigits(value); return digits == null ? "none" : digits.substring(0, Math.min(2, digits.length())); }
  private String digitCount(String value) { String digits = normalizeDigits(value); return digits == null ? "none" : Integer.toString(digits.length()); }

  /** Claims a transaction atomically before matching, so two replicas cannot allocate it twice. */
  private void tryAutoMatch(BankTransactionDocument candidate) {
    if (payments == null || intents == null || mongo == null || candidate == null || candidate.id == null || candidate.amount <= 0 || !"IN".equals(candidate.direction) || !"NEEDS_REVIEW".equals(candidate.reconciliationStatus)) return;
    Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(candidate.id), Criteria.where("reconciliationStatus").is("NEEDS_REVIEW")));
    Update claim = new Update().set("reconciliationStatus", "AUTO_MATCHING");
    BankTransactionDocument claimed = mongo.findAndModify(query, claim, FindAndModifyOptions.options().returnNew(true), BankTransactionDocument.class);
    if (claimed == null) return;
    try {
      // Amount/status/expiry are indexed, so a bank alert does not scan every
      // historical pending intent before checking the exact opaque content.
      List<PaymentIntentDocument> matches = intents.findByStatusAndTotalAmountAndExpiresAtAfter(IntentStatus.PENDING, claimed.amount, Instant.now()).stream().filter(i -> exactIntentMatch(i, claimed)).toList();
      // Two equally valid pending intents are a financial conflict. Never
      // pick by creation time, amount, or longest text match.
      PaymentIntentDocument intent = matches.size() == 1 ? matches.getFirst() : null;
      boolean ambiguousIntent = matches.size() > 1;
      List<MemberDocument> directCandidates = intent == null && !ambiguousIntent ? memberCandidates(claimed.parsedContent) : List.of();
      Integer annualYear = null;
      if (intent == null && !ambiguousIntent && references != null) {
        // Payment references are opaque 10-character tokens. Extracting tokens
        // from the parsed description and looking them up by the unique code
        // index avoids scanning the entire reference collection on every mail.
        var annualMatches = java.util.regex.Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{10}(?![A-Z0-9])")
            .matcher(claimed.parsedContent == null ? "" : claimed.parsedContent.toUpperCase(java.util.Locale.ROOT));
        java.util.Map<String, com.fundmanagement.api.domain.PaymentReferenceDocument> found = new java.util.HashMap<>();
        while (annualMatches.find()) references.findByCode(annualMatches.group()).filter(ref -> ref.year != null).ifPresent(ref -> found.put(ref.code, ref));
      if (found.size() == 1) {
          var annual = found.values().iterator().next();
          // A legacy reference may coexist with a stable userid/name in a
          // migrated message. Only trust it when it agrees with the direct
          // candidate; conflicting identity signals go to review.
          if (directCandidates.isEmpty() || (directCandidates.size() == 1 && directCandidates.getFirst().id.equals(annual.memberId))) {
            intent = new PaymentIntentDocument(); intent.memberId = annual.memberId; annualYear = annual.year;
          } else {
            ambiguousIntent = true;
            claimed.conflictReason = "LEGACY_REFERENCE_CONFLICT";
          }
        }
      }
      if (intent == null && !ambiguousIntent) {
        if (directCandidates.size() == 1) {
          intent = new PaymentIntentDocument(); intent.memberId = directCandidates.getFirst().id;
          claimed.matchCandidates = candidates(directCandidates, claimed.parsedContent);
        } else {
          claimed.matchCandidates = candidates(directCandidates, claimed.parsedContent);
          claimed.conflictReason = directCandidates.isEmpty() ? "NO_MEMBER_IDENTIFIER" : "AMBIGUOUS_MEMBER_MATCH";
          claimed.reconciliationStatus = "NEEDS_REVIEW"; transactions.save(claimed); return;
        }
      }
      if (intent == null) {
        if (claimed.conflictReason == null) claimed.conflictReason = "MULTIPLE_PENDING_INTENTS";
        claimed.reconciliationStatus = "NEEDS_REVIEW"; transactions.save(claimed); return;
      }
      String key = "gmail:" + claimed.providerMessageId;
      ApiDtos.ManualPaymentResponse payment = annualYear == null
          ? (matches.size() == 1 ? payments.recordForIntent(intent, claimed.amount, claimed.occurredAt, key, "system:gmail-auto")
              : payments.recordAutomatic(intent.memberId, claimed.amount, claimed.occurredAt, key, java.time.YearMonth.from(claimed.occurredAt.atZone(java.time.ZoneOffset.UTC)).getYear(), "system:gmail-auto"))
          : payments.recordAutomatic(intent.memberId, claimed.amount, claimed.occurredAt, key, annualYear, "system:gmail-auto");
      claimed.matchMethod = matches.size() == 1 ? "PAYMENT_INTENT" : annualYear != null ? "LEGACY_REFERENCE" : "MEMBER_IDENTIFIER";
      claimed.conflictReason = null; claimed.matchCandidates = new ArrayList<>(); claimed.reconciliationStatus = "MATCHED"; claimed.matchedMemberId = intent.memberId; claimed.matchedDuePeriodIds = payment.allocations().stream().map(ApiDtos.AllocationResponse::duePeriodId).toList(); claimed.reviewedBy = "system:gmail-auto"; claimed.reviewedAt = Instant.now(); transactions.save(claimed);
      if (audit != null) audit.record("system:gmail-auto", "BANK_TRANSACTION_AUTO_MATCHED", "bank_transaction", claimed.id, java.util.Map.of("status", "NEEDS_REVIEW"), java.util.Map.of("status", "MATCHED", "amount", claimed.amount));
    } catch (RuntimeException failure) { claimed.conflictReason = "AUTO_MATCH_FAILURE"; claimed.reconciliationStatus = "NEEDS_REVIEW"; transactions.save(claimed); }
  }
  private boolean exactIntentMatch(PaymentIntentDocument intent, BankTransactionDocument tx) {
    if (intent == null || intent.status != IntentStatus.PENDING || intent.totalAmount != tx.amount || intent.transferContent == null || intent.transferContent.isBlank() || intent.createdAt == null || tx.occurredAt == null) return false;
    boolean contentMatches = transferMatches(intent.transferContent, tx.parsedContent);
    if (!contentMatches && members != null) {
      contentMatches = members.findById(intent.memberId).map(member -> memberIdentifierMatches(member, tx.parsedContent)).orElse(false);
    }
    if (!contentMatches) return false;
    long window = gmail == null ? 300 : Math.max(1, gmail.getIntentMatchSeconds());
    Duration age = Duration.between(intent.createdAt, tx.occurredAt);
    return !age.isNegative() && age.getSeconds() <= window && (intent.expiresAt == null || !tx.occurredAt.isAfter(intent.expiresAt));
  }
  private boolean transferMatches(String expected, String actual) {
    if (actual == null) return false;
    String content = normalizeForMatching(actual);
    String code = normalizeForMatching(expected);
    if (code.isBlank()) return false;
    return java.util.regex.Pattern.compile("(?:^|[^a-z0-9_-])" + java.util.regex.Pattern.quote(code) + "(?:$|[^a-z0-9_-])").matcher(content).find();
  }
  /** Finds exact member-code or exact full-name phrases, never a partial name. */
  private List<MemberDocument> memberCandidates(String actual) {
    if (members == null || actual == null || actual.isBlank()) return List.of();
    String content = normalizeForMatching(actual);
    Map<String, MemberDocument> found = new LinkedHashMap<>();
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      boolean code = transferMatches(member.memberCode, actual);
      boolean name = member.fullName != null && phraseMatches(member.fullName, content);
      if (code || name) found.put(member.id, member);
    }
    return List.copyOf(found.values());
  }
  private boolean memberIdentifierMatches(MemberDocument member, String actual) {
    return member != null && (transferMatches(member.memberCode, actual) || (member.fullName != null && phraseMatches(member.fullName, normalizeForMatching(actual))));
  }
  private boolean phraseMatches(String phrase, String normalizedContent) {
    String normalizedPhrase = normalizeForMatching(phrase);
    if (normalizedPhrase.isBlank()) return false;
    return java.util.regex.Pattern.compile("(?:^|[^a-z0-9_-])" + java.util.regex.Pattern.quote(normalizedPhrase) + "(?:$|[^a-z0-9_-])").matcher(normalizedContent).find();
  }
  private String normalizeForMatching(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", " ").replaceAll("\\s+", " ").trim();
  }
  private List<BankTransactionDocument.MatchCandidate> candidates(List<MemberDocument> members, String content) {
    if (members == null || members.isEmpty()) return List.of();
    List<BankTransactionDocument.MatchCandidate> result = new ArrayList<>();
    for (MemberDocument member : members) {
      BankTransactionDocument.MatchCandidate candidate = new BankTransactionDocument.MatchCandidate(); candidate.memberId = member.id; candidate.confidence = 1.0; candidate.signals = new ArrayList<>();
      if (transferMatches(member.memberCode, content)) candidate.signals.add("MEMBER_CODE");
      if (member.fullName != null && phraseMatches(member.fullName, normalizeForMatching(content))) candidate.signals.add("FULL_NAME");
      result.add(candidate);
    }
    return result;
  }
  public ApiDtos.ReconciliationPageResponse reviewQueue(int page, int size) { var result = transactions.findByReconciliationStatusOrderByOccurredAtDesc("NEEDS_REVIEW", org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))); return new ApiDtos.ReconciliationPageResponse(result.getContent().stream().map(this::response).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }
  public ApiDtos.ReconciliationPageResponse matchedQueue(int page, int size) { var result = transactions.findByReconciliationStatusOrderByOccurredAtDesc("MATCHED", org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))); return new ApiDtos.ReconciliationPageResponse(result.getContent().stream().map(this::response).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }
  public ApiDtos.BankTransactionResponse response(BankTransactionDocument tx) {
    List<ApiDtos.ReconciliationMatchCandidate> candidates = tx.matchCandidates == null ? List.of() : tx.matchCandidates.stream()
        .map(candidate -> new ApiDtos.ReconciliationMatchCandidate(candidate.memberId, candidate.confidence, candidate.signals == null ? List.of() : List.copyOf(candidate.signals))).toList();
    return new ApiDtos.BankTransactionResponse(tx.id, tx.providerMessageId, tx.occurredAt, tx.amount, tx.direction, tx.parsedContent, tx.availableBalance, tx.reconciliationStatus, tx.matchedMemberId, tx.matchedDuePeriodIds, tx.createdAt, tx.matchMethod, tx.conflictReason, candidates);
  }
}
