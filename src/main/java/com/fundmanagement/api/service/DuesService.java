package com.fundmanagement.api.service;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ContributionPeriod;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class DuesService {
  private final MemberRepository members; private final DuePeriodRepository dues; private final PaymentIntentRepository intents; private final MemberService memberService; private final FundProperties properties; private final AuditService audit; private final SettingsService settings; private final PaymentDetectionWindowService detectionWindow; private final PaymentReferenceService paymentReferences; private final java.util.Optional<GmailPollingWorker> gmailPollingWorker; private final TaskExecutor taskExecutor;
  private MongoTemplate mongoTemplate;
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
  @Autowired public void setMongoTemplate(MongoTemplate mongoTemplate) { this.mongoTemplate = mongoTemplate; }
  public ApiDtos.GenerationResult generate(String month) {
    parseMonth(month); int created = 0, skipped = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      if (month.compareTo(member.baselineMonth) < 0 || dues.findByMemberIdAndMonth(member.id, month).isPresent()) { skipped++; continue; }
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month; due.amountDue = memberService.rateFor(member, month); due.amountPaid = initiallyPaid(member, month) ? due.amountDue : 0; due.status = status(due);
      // New payment flows identify the payer by the stable member code. Keep
      // the legacy reference service injectable so old data can still be
      // reconciled, but do not mint random references while generating dues.
      try { dues.save(due); created++; } catch (DuplicateKeyException duplicate) { skipped++; }
    }
    return new ApiDtos.GenerationResult(month, created, skipped);
  }

  /** Generates the configured master plan for one calendar year. This is intended for onboarding/import and is idempotent. */
  public ApiDtos.YearGenerationResult generateYear(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    int created = 0, skipped = 0, initiallyPaid = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
      int count = 14;
      ContributionPeriod.validateRegularMonth(start);
      for (String period : ContributionPeriod.yearPeriods(year)) {
        if (!ContributionPeriod.inPlan(period, start, count)) { skipped++; continue; }
        if (dues.findByMemberIdAndMonth(member.id, period).isPresent()) { skipped++; continue; }
        DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = period; due.amountDue = memberService.rateFor(member, due.month); due.amountPaid = initiallyPaid(member, due.month) ? due.amountDue : 0; due.seededPaid = due.amountPaid > 0; due.status = status(due);
        try { dues.save(due); created++; if (due.amountPaid > 0) initiallyPaid++; } catch (DuplicateKeyException duplicate) { skipped++; }
      }
    }
    return new ApiDtos.YearGenerationResult(year, created, skipped, initiallyPaid);
  }

  /** Returns the explicit per-member master plan used by the admin grid. */
  public ApiDtos.PaymentPlanPageResponse listPaymentPlans(int year) {
    validateYear(year);
    List<ApiDtos.PaymentPlanResponse> plans = members.findByStatus(MemberStatus.ACTIVE).stream()
        .sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName))
        .map(member -> paymentPlanResponse(member, year)).toList();
    return new ApiDtos.PaymentPlanPageResponse(plans, year);
  }

  /**
   * Saves the admin master plan idempotently and materializes the requested
   * year's due periods. Paid seeds are provenance markers only: no manual
   * payment, income, or bank allocation is created.
   */
  public ApiDtos.PaymentPlanResponse savePaymentPlan(String memberId, ApiDtos.PaymentPlanRequest request, String actor) {
    validateYear(request.year());
    MemberDocument member = members.findById(memberId).orElseThrow(() -> new IllegalArgumentException("Member not found"));
    if (request.expectedVersion() != null && member.version != null && !request.expectedVersion().equals(member.version)) {
      throw new org.springframework.dao.OptimisticLockingFailureException("Member payment plan was changed by another user");
    }
    String start = request.contributionStartMonth();
    ContributionPeriod.validateRegularMonth(start);
    if (Integer.parseInt(start.substring(0, 4)) > request.year()) throw new IllegalArgumentException("Contribution start month cannot be after plan year");
    int count = request.annualContributionMonths() == null ? 14 : request.annualContributionMonths();
    if (count < 1 || count > 14) throw new IllegalArgumentException("annualContributionMonths must be between 1 and 14");
    count = 14;
    List<String> paid = request.paidPeriods() == null ? List.of() : request.paidPeriods().stream().distinct().sorted(ContributionPeriod.comparator()).toList();
    for (String period : paid) { ContributionPeriod.validate(period); if (ContributionPeriod.year(period) != request.year()) throw new IllegalArgumentException("paidPeriods must belong to the requested year"); if (!ContributionPeriod.inPlan(period, start, count)) throw new IllegalArgumentException("paidPeriods contains a period outside the configured plan"); }
    Map<String, Object> before = Map.of("contributionStartMonth", planStart(member), "annualContributionMonths", planMonths(member), "initiallyPaidMonths", member.initiallyPaidMonths == null ? List.of() : List.copyOf(member.initiallyPaidMonths));
    List<DuePeriodDocument> existing = dues.findPlanByMemberIdAndMonthBetween(member.id, ContributionPeriod.key(request.year(), 1), ContributionPeriod.key(request.year(), 14));
    Map<String, DuePeriodDocument> byPeriod = existing.stream().collect(java.util.stream.Collectors.toMap(d -> d.month, d -> d, (a, b) -> a));
    for (String period : ContributionPeriod.yearPeriods(request.year())) {
      if (!ContributionPeriod.inPlan(period, start, count)) continue;
      DuePeriodDocument due = byPeriod.get(period);
      boolean shouldSeed = paid.contains(period);
      if (due == null) {
        due = new DuePeriodDocument(); due.memberId = member.id; due.month = period; due.amountDue = memberService.rateFor(member, period); due.amountPaid = shouldSeed ? due.amountDue : 0; due.seededPaid = shouldSeed; due.status = status(due); dues.save(due); byPeriod.put(period, due);
      } else {
        if (due.amountDue <= 0) due.amountDue = memberService.rateFor(member, period);
        if (shouldSeed && due.amountPaid > 0 && due.amountPaid < due.amountDue && !due.seededPaid) throw new IllegalArgumentException("Cannot seed a partially paid period");
        if (shouldSeed && due.amountPaid == 0) { due.amountPaid = due.amountDue; due.seededPaid = true; }
        if (!shouldSeed && due.seededPaid && due.payments != null && due.payments.isEmpty()) { due.amountPaid = 0; due.seededPaid = false; }
        if (!shouldSeed && due.amountPaid > 0 && !due.seededPaid) throw new IllegalArgumentException("Cannot downgrade a ledger-paid period");
        due.status = status(due); dues.save(due);
      }
    }
    // Existing periods outside the new plan cannot be silently deleted or
    // downgraded. They remain historical records and are not part of the plan.
    member.contributionStartMonth = start;
    member.annualContributionMonths = count;
    List<String> retained = member.initiallyPaidMonths == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(member.initiallyPaidMonths);
    retained.removeIf(value -> ContributionPeriod.isValid(value) && ContributionPeriod.year(value) == request.year());
    retained.addAll(paid);
    retained = retained.stream().distinct().sorted(ContributionPeriod.comparator()).toList();
    member.initiallyPaidMonths = new java.util.ArrayList<>(retained);
    persistPlanMember(member, start, count, retained);
    Map<String, Object> after = Map.of("contributionStartMonth", start, "annualContributionMonths", count, "initiallyPaidMonths", List.copyOf(retained));
    if (audit != null) audit.record(actor == null ? "unknown" : actor, "PAYMENT_PLAN_SAVED", "member", member.id, before, after);
    return paymentPlanResponse(member, request.year());
  }

  /**
   * Imported members predate @Version and therefore have no version field.
   * Repository.save treats those hydrated documents as new inserts, which
   * collides on _id. Update the legacy shape in place and initialize version
   * atomically; versioned members continue through the normal optimistic save.
   */
  private void persistPlanMember(MemberDocument member, String start, int count, List<String> retained) {
    if (member.version != null || mongoTemplate == null) { members.save(member); return; }
    Query query = Query.query(new Criteria().andOperator(
        Criteria.where("_id").is(member.id),
        new Criteria().orOperator(Criteria.where("version").exists(false), Criteria.where("version").is(null))));
    Update update = new Update()
        .set("contributionStartMonth", start)
        .set("annualContributionMonths", count)
        .set("initiallyPaidMonths", retained)
        .set("version", 1L);
    if (mongoTemplate.updateFirst(query, update, MemberDocument.class).getMatchedCount() != 1) {
      throw new org.springframework.dao.OptimisticLockingFailureException("Member payment plan was changed by another user");
    }
    member.version = 1L;
  }

  private ApiDtos.PaymentPlanResponse paymentPlanResponse(MemberDocument member, int year) {
    String start = planStart(member);
    int count = planMonths(member);
    List<DuePeriodDocument> existing = dues.findPlanByMemberIdAndMonthBetween(member.id, ContributionPeriod.key(year, 1), ContributionPeriod.key(year, 14));
    Map<String, DuePeriodDocument> byPeriod = existing.stream().collect(java.util.stream.Collectors.toMap(d -> d.month, d -> d, (a, b) -> a));
    List<String> paid = member.initiallyPaidMonths == null ? List.of() : member.initiallyPaidMonths.stream().filter(ContributionPeriod::isValid).filter(value -> ContributionPeriod.year(value) == year).sorted(ContributionPeriod.comparator()).toList();
    List<ApiDtos.MasterPlanPeriod> periods = ContributionPeriod.yearPeriods(year).stream().filter(period -> safeInPlan(period, start, count)).map(period -> {
      DuePeriodDocument due = byPeriod.get(period);
      if (due == null) return new ApiDtos.MasterPlanPeriod(null, period, 0, 0, 0, null, paid.contains(period));
      return new ApiDtos.MasterPlanPeriod(due.id, period, due.amountDue, due.amountPaid, Math.max(0, due.amountDue - due.amountPaid), due.status, due.seededPaid);
    }).toList();
    return new ApiDtos.PaymentPlanResponse(member.id, member.memberCode, member.fullName, year, start, count, paid, periods, member.version);
  }

  private boolean safeInPlan(String period, String start, int count) { return start != null && !start.isBlank() && ContributionPeriod.isRegularMonth(start) && ContributionPeriod.inPlan(period, start, count); }
  private String planStart(MemberDocument member) { return member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth; }
  private int planMonths(MemberDocument member) { return 14; }
  private void validateYear(int year) { if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid"); }

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
    List<ApiDtos.PublicDuePeriodResponse> payable = dues.findByMemberIdOrderByMonthAsc(member.id).stream().filter(p -> inMemberPlan(member, p.month)).filter(this::hasOutstanding).map(p -> new ApiDtos.PublicDuePeriodResponse(p.id, p.month, outstanding(p))).toList();
    recordAudit("PUBLIC_PAYMENT_LOOKUP", member.id);
    return new ApiDtos.PublicPaymentOptionsResponse(false, List.of(new ApiDtos.PublicMemberMatch(member.fullName, isBlank(request.memberCode()) ? null : member.memberCode)), payable);
  }

  /** Indexed member lookup used by the dashboard payer panel; it avoids scanning the active roster. */
  public ApiDtos.PublicPaymentOptionsResponse publicPaymentOptionsByMember(String fullName, String memberCode) { return publicPaymentOptionsByMember(fullName, memberCode, null); }

  /**
   * Returns the safe, public payment ledger for one active member.  The member
   * code is the only public selector; names are returned for display only.
   * No bank transaction, payment reference, email, phone, or audit fields are
   * included in this read model.
   */
  public ApiDtos.PublicPaymentHistoryResponse publicPaymentHistory(String memberCode, Integer year) {
    MemberDocument member = publicMemberByCode(memberCode);
    validateOptionalYear(year);
    List<DuePeriodDocument> periods = dues.findByMemberIdOrderByMonthAsc(member.id).stream()
        .filter(period -> year == null || (period.month != null && period.month.startsWith(year + "-")))
        .filter(period -> inMemberPlan(member, period.month))
        .toList();
    long totalDue = 0;
    long totalPaid = 0;
    List<ApiDtos.PublicPaymentHistoryEntry> entries = new java.util.ArrayList<>();
    for (DuePeriodDocument period : periods) {
      try {
        totalDue = Math.addExact(totalDue, period.amountDue);
        totalPaid = Math.addExact(totalPaid, period.amountPaid);
      } catch (ArithmeticException ex) {
        throw new IllegalArgumentException("Payment totals are too large");
      }
      entries.add(new ApiDtos.PublicPaymentHistoryEntry(period.id, period.month, period.amountDue,
          period.amountPaid, outstanding(period), period.status));
    }
    recordAudit("PUBLIC_PAYMENT_HISTORY_LOOKUP", member.id);
    return new ApiDtos.PublicPaymentHistoryResponse(member.memberCode, member.fullName, year,
        totalDue, totalPaid, Math.max(0, totalDue - totalPaid), List.copyOf(entries));
  }

  /** Alias read model for clients that call the selected member view “detail”. */
  public ApiDtos.PublicPaymentDetailResponse publicPaymentDetail(String memberCode, Integer year) {
    ApiDtos.PublicPaymentHistoryResponse history = publicPaymentHistory(memberCode, year);
    return new ApiDtos.PublicPaymentDetailResponse(history.memberCode(), history.fullName(), history.year(),
        history.totalDue(), history.totalPaid(), history.outstandingAmount(), history.periods());
  }

  private MemberDocument publicMemberByCode(String memberCode) {
    if (isBlank(memberCode) || !memberCode.trim().matches("[A-Za-z0-9_-]{2,32}")) {
      throw new IllegalArgumentException("Member code is invalid");
    }
    return members.findByStatusAndMemberCode(MemberStatus.ACTIVE, memberCode.trim().toUpperCase(Locale.ROOT))
        .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No matching member found"));
  }

  private void validateOptionalYear(Integer year) {
    if (year != null && (year < 2000 || year > 9999)) throw new IllegalArgumentException("Year is invalid");
  }

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
        .filter(p -> inMemberPlan(member, p.month))
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
    if (periods.stream().anyMatch(period -> !inMemberPlan(member, period.month))) {
      throw new IllegalArgumentException("Selected due periods are before the member baseline");
    }
    Set<String> planYears = periods.stream().map(p -> p.month == null || p.month.length() < 4 ? "" : p.month.substring(0, 4)).collect(java.util.stream.Collectors.toSet());
    if (planYears.size() != 1 || planYears.contains("")) throw new IllegalArgumentException("A payment plan must stay within one calendar year");
    long amount = 0;
    for (DuePeriodDocument period : periods) {
      long outstanding = outstanding(period);
      if (outstanding <= 0) throw new IllegalArgumentException("Selected periods have no outstanding balance");
      try { amount = Math.addExact(amount, outstanding); } catch (ArithmeticException ex) { throw new IllegalArgumentException("Payment amount is too large"); }
    }
    String month = periods.stream().map(p -> p.month).min(Comparator.naturalOrder()).orElseThrow();
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.memberId = member.id; intent.duePeriodIds = periods.stream().sorted(Comparator.comparing(p -> p.month)).map(p -> p.id).toList(); intent.totalAmount = amount; intent.referenceYear = Integer.parseInt(month.substring(0, 4)); intent.selectionMode = request.selectionMode() == null || request.selectionMode().isBlank() ? "EXPLICIT" : request.selectionMode(); intent.requestedMonthCount = request.monthCount(); intent.throughMonth = request.throughMonth();
    // The QR is intentionally independent of the public website and annual
    // plan. The stable company userid/memberCode is the only new identifier.
    // referenceYear remains metadata for historical reporting and allocation.
    intent.transferContent = member.memberCode;
    intent.createdAt = Instant.now(); intent.expiresAt = intent.createdAt.plusSeconds(properties.getPaymentIntentMinutes() * 60L); intent.status = IntentStatus.PENDING; intents.save(intent);
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
    List<DuePeriodDocument> outstanding = dues.findByMemberIdOrderByMonthAsc(member.id).stream().filter(p -> inMemberPlan(member, p.month)).filter(this::hasOutstanding).sorted(Comparator.comparing(p -> p.month)).toList();
    if ("ALL_OUTSTANDING".equals(mode)) return outstanding.stream().map(p -> p.id).toList();
    if ("MONTH_COUNT".equals(mode)) { if (request.monthCount() == null) throw new IllegalArgumentException("monthCount is required"); return outstanding.stream().limit(request.monthCount()).map(p -> p.id).toList(); }
    if ("THROUGH_MONTH".equals(mode)) { if (request.throughMonth() == null) throw new IllegalArgumentException("throughMonth is required"); ContributionPeriod.validate(request.throughMonth()); return outstanding.stream().filter(p -> ContributionPeriod.comparator().compare(p.month, request.throughMonth()) <= 0).map(p -> p.id).toList(); }
    throw new IllegalArgumentException("Unsupported payment selection mode");
  }
  private boolean initiallyPaid(MemberDocument member, String month) { return member.initiallyPaidMonths != null && member.initiallyPaidMonths.contains(month); }
  private boolean inMemberPlan(MemberDocument member, String period) {
    String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
    return start != null && ContributionPeriod.isRegularMonth(start) && period != null && ContributionPeriod.isValid(period) && ContributionPeriod.inPlan(period, start, 14);
  }
  /**
   * Materializes the member's configured annual master plan before returning
   * payment options. The board may project missing periods for display, but a
   * QR intent needs durable due IDs. This idempotent, bounded write closes the
   * gap between those two read models without requiring Admin review.
   */
  private void ensureYearPlan(MemberDocument member, int year) {
    String startValue = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
    if (startValue == null || startValue.isBlank()) return;
    if (!ContributionPeriod.isRegularMonth(startValue)) return;
    int count = 14;
    String planEnd = ContributionPeriod.key(year, Math.min(count, 14));
    // Keep the legacy 12-period query shape for old clients/tests while a
    // 14-period plan explicitly includes the two bonus keys.
    List<DuePeriodDocument> existing = dues.findByMemberIdAndMonthBetweenOrderByMonthAsc(member.id, ContributionPeriod.key(year, 1), planEnd);
    Set<String> existingMonths = existing.stream().map(d -> d.month).collect(java.util.stream.Collectors.toSet());
    List<DuePeriodDocument> missing = new java.util.ArrayList<>();
    for (String period : ContributionPeriod.yearPeriods(year)) {
      if (!ContributionPeriod.inPlan(period, startValue, count) || existingMonths.contains(period)) continue;
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = period; due.amountDue = memberService.rateFor(member, due.month); due.amountPaid = initiallyPaid(member, due.month) ? due.amountDue : 0; due.seededPaid = due.amountPaid > 0; due.status = status(due); missing.add(due);
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
