package com.fundmanagement.api.service;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class DuesService {
  private final MemberRepository members; private final DuePeriodRepository dues; private final PaymentIntentRepository intents; private final MemberService memberService; private final FundProperties properties; private final AuditService audit; private final SettingsService settings; private final PaymentDetectionWindowService detectionWindow; private final PaymentReferenceService paymentReferences; private final java.util.Optional<GmailPollingWorker> gmailPollingWorker; private final TaskExecutor taskExecutor;
  /** Test-friendly constructor; production uses the audited constructor below. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties) { this(members, dues, intents, memberService, properties, null, null, null, null, java.util.Optional.empty(), null); }
  /** Test-friendly constructor with persisted settings. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, SettingsService settings) { this(members, dues, intents, memberService, properties, null, settings, null, null, java.util.Optional.empty(), null); }
  /** Test-friendly constructor with settings and the optional detection window. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, SettingsService settings, PaymentDetectionWindowService detectionWindow) { this(members, dues, intents, memberService, properties, null, settings, detectionWindow, null, java.util.Optional.empty(), null); }
  /** Test-friendly constructor with the persisted payment-reference store. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, SettingsService settings, PaymentDetectionWindowService detectionWindow, PaymentReferenceService paymentReferences) { this(members, dues, intents, memberService, properties, null, settings, detectionWindow, paymentReferences, java.util.Optional.empty(), null); }
  /** Compatibility constructor retained for existing focused tests. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, AuditService audit, SettingsService settings, PaymentDetectionWindowService detectionWindow, java.util.Optional<GmailPollingWorker> gmailPollingWorker, TaskExecutor taskExecutor) { this(members, dues, intents, memberService, properties, audit, settings, detectionWindow, null, gmailPollingWorker, taskExecutor); }
  @Autowired public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, AuditService audit, SettingsService settings, PaymentDetectionWindowService detectionWindow, PaymentReferenceService paymentReferences, java.util.Optional<GmailPollingWorker> gmailPollingWorker, @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) { this.members = members; this.dues = dues; this.intents = intents; this.memberService = memberService; this.properties = properties; this.audit = audit; this.settings = settings; this.detectionWindow = detectionWindow; this.paymentReferences = paymentReferences; this.gmailPollingWorker = gmailPollingWorker; this.taskExecutor = taskExecutor; }
  public ApiDtos.GenerationResult generate(String month) {
    parseMonth(month); int created = 0, skipped = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      if (month.compareTo(member.baselineMonth) < 0 || dues.findByMemberIdAndMonth(member.id, month).isPresent()) { skipped++; continue; }
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month; due.amountDue = memberService.rateFor(member, month); due.amountPaid = initiallyPaid(member, month) ? due.amountDue : 0; due.status = status(due);
      try { dues.save(due); if (paymentReferences != null) paymentReferences.referenceFor(member.id, month); created++; } catch (DuplicateKeyException duplicate) { skipped++; }
    }
    return new ApiDtos.GenerationResult(month, created, skipped);
  }

  /** Generates the configured master plan for one calendar year. This is intended for onboarding/import and is idempotent. */
  public ApiDtos.YearGenerationResult generateYear(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    int created = 0, skipped = 0, initiallyPaid = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
      int count = member.annualContributionMonths == null || member.annualContributionMonths < 1 ? 12 : member.annualContributionMonths;
      YearMonth first = YearMonth.of(year, 1); YearMonth last = first.plusMonths(11); YearMonth planStart = YearMonth.parse(start); YearMonth planEnd = planStart.plusMonths(count - 1L);
      for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
        if (month.isBefore(planStart) || month.isAfter(planEnd)) { skipped++; continue; }
        if (dues.findByMemberIdAndMonth(member.id, month.toString()).isPresent()) { skipped++; continue; }
        DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month.toString(); due.amountDue = memberService.rateFor(member, due.month); due.amountPaid = initiallyPaid(member, due.month) ? due.amountDue : 0; due.status = status(due);
        try { dues.save(due); created++; if (due.amountPaid > 0) initiallyPaid++; } catch (DuplicateKeyException duplicate) { skipped++; }
      }
    }
    return new ApiDtos.YearGenerationResult(year, created, skipped, initiallyPaid);
  }

  public ApiDtos.DuePeriodPageResponse list(int page, int size, String month, String memberId) {
    if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page or size");
    PageRequest request = PageRequest.of(page, Math.min(size, 100), Sort.by("month").ascending());
    Page<DuePeriodDocument> result = month != null && !month.isBlank() ? dues.findByMonth(month, request) : memberId != null && !memberId.isBlank() ? dues.findByMemberId(memberId, request) : dues.findAllBy(request);
    return new ApiDtos.DuePeriodPageResponse(result.getContent().stream().map(this::adminResponse).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
  }

  public ApiDtos.AdminDuePeriodResponse detail(String id) { return adminResponse(dues.findById(id).orElseThrow(() -> new IllegalArgumentException("Due period not found"))); }

  public ApiDtos.AdminDuePeriodResponse adjust(String id, ApiDtos.DueAdjustmentRequest request, String actor) {
    DuePeriodDocument due = dues.findById(id).orElseThrow(() -> new IllegalArgumentException("Due period not found"));
    Map<String, Object> before = Map.of("amountDue", due.amountDue, "amountPaid", due.amountPaid, "status", due.status.name(), "notes", due.notes == null ? "" : due.notes);
    due.amountDue = request.amountDue(); due.notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim(); due.status = status(due); dues.save(due);
    Map<String, Object> after = Map.of("amountDue", due.amountDue, "amountPaid", due.amountPaid, "status", due.status.name(), "notes", due.notes == null ? "" : due.notes);
    auditRecord(actor, "DUE_PERIOD_ADJUSTED", "due_period", due.id, before, after);
    return adminResponse(due);
  }

  private ApiDtos.AdminDuePeriodResponse adminResponse(DuePeriodDocument due) { return new ApiDtos.AdminDuePeriodResponse(due.id, due.memberId, due.month, due.amountDue, due.amountPaid, due.status, due.notes, due.version); }
  private DueStatus status(DuePeriodDocument due) { if (due.amountPaid == 0) return DueStatus.UNPAID; if (due.amountPaid < due.amountDue) return DueStatus.PARTIAL; if (due.amountPaid == due.amountDue) return DueStatus.PAID; return DueStatus.OVERPAID; }
  private void auditRecord(String actor, String action, String type, String id, Map<String, Object> before, Map<String, Object> after) { if (audit != null) audit.record(actor, action, type, id, before, after); }
  /** Resolve an anonymous payer by normalized name; a member code is mandatory for collisions. */
  public ApiDtos.PublicPaymentOptionsResponse publicPaymentOptions(ApiDtos.PublicPaymentOptionsRequest request) {
    List<MemberDocument> matches = matchingPublicMembers(request.fullName());
    if (matches.isEmpty()) throw new IllegalArgumentException("No matching member found");
    if (isBlank(request.memberCode()) && matches.size() > 1) {
      return new ApiDtos.PublicPaymentOptionsResponse(true, matches.stream().map(m -> new ApiDtos.PublicMemberMatch(m.fullName, m.memberCode)).toList(), List.of());
    }
    MemberDocument member = resolvePublicMember(matches, request.memberCode());
    List<ApiDtos.PublicDuePeriodResponse> payable = dues.findByMemberIdOrderByMonthAsc(member.id).stream().filter(this::hasOutstanding).map(p -> new ApiDtos.PublicDuePeriodResponse(p.id, p.month, outstanding(p))).toList();
    recordAudit("PUBLIC_PAYMENT_LOOKUP", member.id);
    return new ApiDtos.PublicPaymentOptionsResponse(false, List.of(new ApiDtos.PublicMemberMatch(member.fullName, isBlank(request.memberCode()) ? null : member.memberCode)), payable);
  }

  /** Indexed member lookup used by the dashboard payer panel; it avoids scanning the active roster. */
  public ApiDtos.PublicPaymentOptionsResponse publicPaymentOptionsByMember(String fullName, String memberCode) { return publicPaymentOptionsByMember(fullName, memberCode, null); }

  /** Indexed member lookup with an optional year bound so the payer page never downloads other annual plans. */
  public ApiDtos.PublicPaymentOptionsResponse publicPaymentOptionsByMember(String fullName, String memberCode, Integer year) {
    List<MemberDocument> matches;
    // The member code is the canonical public picker key. Prefer it whenever
    // supplied; names are convenience text and may differ in Unicode accents
    // after legacy imports. This also lets the frontend send both fields
    // without turning a valid code into a false name-match failure.
    if (!isBlank(memberCode)) matches = members.findByStatusAndMemberCode(MemberStatus.ACTIVE, memberCode.trim().toUpperCase(Locale.ROOT));
    else matches = members.findByStatusAndNormalizedFullName(MemberStatus.ACTIVE, memberService.normalizeName(fullName));
    if (matches.isEmpty()) throw new IllegalArgumentException("No matching member found");
    if (isBlank(memberCode) && matches.size() > 1) return new ApiDtos.PublicPaymentOptionsResponse(true, matches.stream().map(m -> new ApiDtos.PublicMemberMatch(m.fullName, m.memberCode)).toList(), List.of());
    MemberDocument member = resolvePublicMember(matches, memberCode);
    if (year != null && (year < 2000 || year > 9999)) throw new IllegalArgumentException("Year is invalid");
    if (year != null) ensureYearPlan(member, year);
    List<ApiDtos.PublicDuePeriodResponse> payable = dues.findPublicByMemberIdOrderByMonthAsc(member.id).stream()
        .filter(p -> year == null || (p.month != null && p.month.startsWith(year + "-")))
        .filter(this::hasOutstanding)
        .map(p -> new ApiDtos.PublicDuePeriodResponse(p.id, p.month, outstanding(p))).toList();
    recordAudit("PUBLIC_PAYMENT_LOOKUP", member.id);
    return new ApiDtos.PublicPaymentOptionsResponse(false, List.of(new ApiDtos.PublicMemberMatch(member.fullName, isBlank(memberCode) ? null : member.memberCode)), payable);
  }

  /** Creates a server-priced, expiring intent without requiring a member login. */
  public ApiDtos.PublicPaymentIntentResponse createPublicIntent(ApiDtos.PublicPaymentIntentRequest request) {
    List<MemberDocument> matches = matchingPublicMembers(request.fullName());
    if (matches.isEmpty()) throw new IllegalArgumentException("No matching member found");
    MemberDocument member = resolvePublicMember(matches, request.memberCode());
    List<String> requestedIds = resolveSelection(member, request);
    Set<String> requested = new HashSet<>(requestedIds);
    if (requested.size() != requestedIds.size()) throw new IllegalArgumentException("Due periods must not be duplicated");
    List<DuePeriodDocument> periods = dues.findByMemberIdAndIdIn(member.id, requested);
    if (periods.size() != requested.size()) throw new IllegalArgumentException("One or more due periods are invalid");
    Set<String> planYears = periods.stream().map(p -> p.month == null || p.month.length() < 4 ? "" : p.month.substring(0, 4)).collect(java.util.stream.Collectors.toSet());
    if (planYears.size() != 1 || planYears.contains("")) throw new IllegalArgumentException("A payment plan must stay within one calendar year");
    long amount = 0;
    for (DuePeriodDocument period : periods) {
      long outstanding = outstanding(period);
      if (outstanding <= 0) throw new IllegalArgumentException("Selected periods have no outstanding balance");
      try { amount = Math.addExact(amount, outstanding); } catch (ArithmeticException ex) { throw new IllegalArgumentException("Payment amount is too large"); }
    }
    String month = periods.stream().map(p -> p.month).min(Comparator.naturalOrder()).orElseThrow();
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.memberId = member.id; intent.duePeriodIds = periods.stream().sorted(Comparator.comparing(p -> p.month)).map(p -> p.id).toList(); intent.totalAmount = amount; intent.referenceYear = Integer.parseInt(month.substring(0, 4)); intent.selectionMode = request.selectionMode() == null || request.selectionMode().isBlank() ? "EXPLICIT" : request.selectionMode(); intent.requestedMonthCount = request.monthCount(); intent.throughMonth = request.throughMonth(); intent.transferContent = annualReference(member.id, intent.referenceYear, month); intent.createdAt = Instant.now(); intent.expiresAt = intent.createdAt.plusSeconds(properties.getPaymentIntentMinutes() * 60L); intent.status = IntentStatus.PENDING; intents.save(intent);
    if (detectionWindow != null) detectionWindow.open(java.time.Duration.ofMinutes(5));
    triggerImmediateGmailPoll();
    recordAudit("PUBLIC_PAYMENT_INTENT_CREATED", member.id);
    return toPublicResponse(intent);
  }
  public ApiDtos.DuePeriodResponse toResponse(DuePeriodDocument p) { return new ApiDtos.DuePeriodResponse(p.id, p.month, p.amountDue, p.amountPaid, p.status, p.notes); }
  public ApiDtos.PublicPaymentIntentStatusResponse publicIntentStatus(String id) {
    PaymentIntentDocument intent = intents.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
    IntentStatus status = intent.status;
    if (status == IntentStatus.PENDING && intent.expiresAt != null && intent.expiresAt.isBefore(Instant.now())) { intent.status = IntentStatus.EXPIRED; intents.save(intent); status = IntentStatus.EXPIRED; }
    return new ApiDtos.PublicPaymentIntentStatusResponse(intent.id, status, intent.expiresAt);
  }
  /** Returns shareable payment details without exposing payer identity or internal transaction data. */
  public ApiDtos.PublicPaymentIntentDetailsResponse publicIntentDetails(String id) {
    publicIntentStatus(id);
    PaymentIntentDocument intent = intents.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
    ApiDtos.PublicPaymentIntentResponse response = toPublicResponse(intent);
    return new ApiDtos.PublicPaymentIntentDetailsResponse(response.id(), response.amount(), response.transferContent(), response.qrImageUrl(), response.accountHolder(), response.accountNumber(), response.bankName(), response.expiresAt(), response.status());
  }
  private List<String> resolveSelection(MemberDocument member, ApiDtos.PublicPaymentIntentRequest request) {
    String mode = request.selectionMode() == null || request.selectionMode().isBlank() ? "EXPLICIT" : request.selectionMode();
    if ("EXPLICIT".equals(mode)) return request.duePeriodIds() == null ? List.of() : request.duePeriodIds();
    List<DuePeriodDocument> outstanding = dues.findByMemberIdOrderByMonthAsc(member.id).stream().filter(this::hasOutstanding).sorted(Comparator.comparing(p -> p.month)).toList();
    if ("ALL_OUTSTANDING".equals(mode)) return outstanding.stream().map(p -> p.id).toList();
    if ("MONTH_COUNT".equals(mode)) { if (request.monthCount() == null) throw new IllegalArgumentException("monthCount is required"); return outstanding.stream().limit(request.monthCount()).map(p -> p.id).toList(); }
    if ("THROUGH_MONTH".equals(mode)) { if (request.throughMonth() == null) throw new IllegalArgumentException("throughMonth is required"); parseMonth(request.throughMonth()); return outstanding.stream().filter(p -> p.month.compareTo(request.throughMonth()) <= 0).map(p -> p.id).toList(); }
    throw new IllegalArgumentException("Unsupported payment selection mode");
  }
  private String annualReference(String memberId, int year, String legacyMonth) {
    if (paymentReferences == null) return "QUY " + legacyMonth.substring(5, 7) + legacyMonth.substring(0, 4);
    String annual = paymentReferences.referenceForYear(memberId, year);
    // Mockito/legacy test doubles may not implement annual allocation yet; preserve the old reference fallback.
    return annual == null || annual.isBlank() ? paymentReferences.referenceFor(memberId, legacyMonth) : annual;
  }
  private boolean initiallyPaid(MemberDocument member, String month) { return member.initiallyPaidMonths != null && member.initiallyPaidMonths.contains(month); }
  /**
   * Materializes the member's configured annual master plan before returning
   * payment options. The board may project missing periods for display, but a
   * QR intent needs durable due IDs. This idempotent, bounded write closes the
   * gap between those two read models without requiring Admin review.
   */
  private void ensureYearPlan(MemberDocument member, int year) {
    String startValue = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
    if (startValue == null || startValue.isBlank()) return;
    YearMonth planStart;
    try { planStart = YearMonth.parse(startValue); } catch (RuntimeException ex) { return; }
    int count = member.annualContributionMonths == null || member.annualContributionMonths < 1 ? 12 : member.annualContributionMonths;
    YearMonth planEnd = planStart.plusMonths(count - 1L);
    YearMonth first = YearMonth.of(year, 1), last = YearMonth.of(year, 12);
    if (planEnd.isBefore(first) || planStart.isAfter(last)) return;
    List<DuePeriodDocument> existing = dues.findByMemberIdAndMonthBetweenOrderByMonthAsc(member.id, first.toString(), last.toString());
    Set<String> existingMonths = existing.stream().map(d -> d.month).collect(java.util.stream.Collectors.toSet());
    List<DuePeriodDocument> missing = new java.util.ArrayList<>();
    for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
      if (month.isBefore(planStart) || month.isAfter(planEnd) || existingMonths.contains(month.toString())) continue;
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month.toString(); due.amountDue = memberService.rateFor(member, due.month); due.amountPaid = initiallyPaid(member, due.month) ? due.amountDue : 0; due.status = status(due); missing.add(due);
    }
    if (!missing.isEmpty()) {
      try { dues.saveAll(missing); } catch (DuplicateKeyException ignored) { /* another request materialized the same plan */ }
    }
  }
  private long outstanding(DuePeriodDocument period) { return Math.max(0, period.amountDue - period.amountPaid); }
  private boolean hasOutstanding(DuePeriodDocument period) { return outstanding(period) > 0; }
  private List<MemberDocument> matchingPublicMembers(String fullName) { String normalized = memberService.normalizeName(fullName); return members.findByStatus(MemberStatus.ACTIVE).stream().filter(m -> normalized.equals(memberService.normalizeName(m.fullName))).toList(); }
  private MemberDocument resolvePublicMember(List<MemberDocument> matches, String memberCode) { if (matches.size() > 1 && isBlank(memberCode)) throw new IllegalArgumentException("Member code is required for duplicate names"); String normalizedCode = isBlank(memberCode) ? null : memberCode.trim().toUpperCase(Locale.ROOT); return matches.stream().filter(m -> normalizedCode == null || normalizedCode.equals(m.memberCode)).findFirst().orElseThrow(() -> new IllegalArgumentException("No matching member found")); }
  private boolean isBlank(String value) { return value == null || value.isBlank(); }
  private void triggerImmediateGmailPoll() {
    if (taskExecutor == null) return;
    gmailPollingWorker.ifPresent(worker -> { try { taskExecutor.execute(worker::pollNow); } catch (RuntimeException ignored) { /* QR creation remains available if the background executor is saturated. */ } });
  }
  private void recordAudit(String action, String memberId) { if (audit != null) audit.record("anonymous", action, "member", memberId, Map.of(), Map.of()); }
  private ApiDtos.PublicPaymentIntentResponse toPublicResponse(PaymentIntentDocument intent) { ApiDtos.SettingsResponse current = currentSettings(); String url = properties.getQrImageBaseUrl() + "/" + current.bankCode() + "-" + current.accountNumber() + "-compact2.png?amount=" + intent.totalAmount + "&addInfo=" + URLEncoder.encode(intent.transferContent, StandardCharsets.UTF_8) + "&accountName=" + URLEncoder.encode(current.accountHolder(), StandardCharsets.UTF_8); List<ApiDtos.PaymentPlanPeriod> plan = dues.findAllById(intent.duePeriodIds).stream().sorted(Comparator.comparing(p -> p.month)).map(p -> new ApiDtos.PaymentPlanPeriod(p.id, p.month, p.amountDue, p.amountPaid, outstanding(p))).toList(); return new ApiDtos.PublicPaymentIntentResponse(intent.id, intent.totalAmount, intent.transferContent, url, current.accountHolder(), current.accountNumber(), current.bankName(), intent.expiresAt, intent.status, plan); }
  private ApiDtos.SettingsResponse currentSettings() { if (settings != null) return settings.get(); return new ApiDtos.SettingsResponse(properties.getDefaultContributionAmount(), properties.getAutoReconciliationThreshold(), properties.getBankName(), properties.getBankCode(), properties.getAccountNumber(), properties.getAccountHolder(), properties.isDueSchedulerEnabled(), properties.isStartupCatchUpEnabled(), properties.isIngestionEnabled(), properties.isGmailEnabled(), properties.getGmailSenderFilter(), properties.isSmtpEnabled(), properties.getSmtpHost(), properties.getSmtpPort(), properties.getSmtpFrom()); }
  private void parseMonth(String month) { try { YearMonth.parse(month); } catch (RuntimeException ex) { throw new IllegalArgumentException("Month must be YYYY-MM"); } }
}
