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
import org.springframework.stereotype.Service;

@Service
public class DuesService {
  private final MemberRepository members; private final DuePeriodRepository dues; private final PaymentIntentRepository intents; private final MemberService memberService; private final FundProperties properties; private final AuditService audit; private final SettingsService settings; private final PaymentDetectionWindowService detectionWindow;
  /** Test-friendly constructor; production uses the audited constructor below. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties) { this(members, dues, intents, memberService, properties, null, null, null); }
  /** Test-friendly constructor with persisted settings. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, SettingsService settings) { this(members, dues, intents, memberService, properties, null, settings, null); }
  /** Test-friendly constructor with settings and the optional detection window. */
  public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, SettingsService settings, PaymentDetectionWindowService detectionWindow) { this(members, dues, intents, memberService, properties, null, settings, detectionWindow); }
  @Autowired public DuesService(MemberRepository members, DuePeriodRepository dues, PaymentIntentRepository intents, MemberService memberService, FundProperties properties, AuditService audit, SettingsService settings, PaymentDetectionWindowService detectionWindow) { this.members = members; this.dues = dues; this.intents = intents; this.memberService = memberService; this.properties = properties; this.audit = audit; this.settings = settings; this.detectionWindow = detectionWindow; }
  public ApiDtos.GenerationResult generate(String month) {
    parseMonth(month); int created = 0, skipped = 0;
    for (MemberDocument member : members.findByStatus(MemberStatus.ACTIVE)) {
      if (month.compareTo(member.baselineMonth) < 0 || dues.findByMemberIdAndMonth(member.id, month).isPresent()) { skipped++; continue; }
      DuePeriodDocument due = new DuePeriodDocument(); due.memberId = member.id; due.month = month; due.amountDue = memberService.rateFor(member, month); due.amountPaid = 0; due.status = DueStatus.UNPAID;
      try { dues.save(due); created++; } catch (DuplicateKeyException duplicate) { skipped++; }
    }
    return new ApiDtos.GenerationResult(month, created, skipped);
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

  /** Creates a server-priced, expiring intent without requiring a member login. */
  public ApiDtos.PublicPaymentIntentResponse createPublicIntent(ApiDtos.PublicPaymentIntentRequest request) {
    List<MemberDocument> matches = matchingPublicMembers(request.fullName());
    if (matches.isEmpty()) throw new IllegalArgumentException("No matching member found");
    MemberDocument member = resolvePublicMember(matches, request.memberCode());
    Set<String> requested = new HashSet<>(request.duePeriodIds());
    if (requested.size() != request.duePeriodIds().size()) throw new IllegalArgumentException("Due periods must not be duplicated");
    List<DuePeriodDocument> periods = dues.findByMemberIdAndIdIn(member.id, requested);
    if (periods.size() != requested.size()) throw new IllegalArgumentException("One or more due periods are invalid");
    long amount = 0;
    for (DuePeriodDocument period : periods) {
      long outstanding = outstanding(period);
      if (outstanding <= 0) throw new IllegalArgumentException("Selected periods have no outstanding balance");
      try { amount = Math.addExact(amount, outstanding); } catch (ArithmeticException ex) { throw new IllegalArgumentException("Payment amount is too large"); }
    }
    String month = periods.stream().map(p -> p.month).min(Comparator.naturalOrder()).orElseThrow();
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.memberId = member.id; intent.duePeriodIds = periods.stream().map(p -> p.id).toList(); intent.totalAmount = amount; intent.transferContent = "QUY " + member.memberCode + " " + month.substring(5, 7) + month.substring(0, 4); intent.createdAt = Instant.now(); intent.expiresAt = intent.createdAt.plusSeconds(properties.getPaymentIntentMinutes() * 60L); intent.status = IntentStatus.PENDING; intents.save(intent);
    if (detectionWindow != null) detectionWindow.open(java.time.Duration.ofMinutes(5));
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
  private long outstanding(DuePeriodDocument period) { return Math.max(0, period.amountDue - period.amountPaid); }
  private boolean hasOutstanding(DuePeriodDocument period) { return outstanding(period) > 0; }
  private List<MemberDocument> matchingPublicMembers(String fullName) { String normalized = memberService.normalizeName(fullName); return members.findByStatus(MemberStatus.ACTIVE).stream().filter(m -> normalized.equals(memberService.normalizeName(m.fullName))).toList(); }
  private MemberDocument resolvePublicMember(List<MemberDocument> matches, String memberCode) { if (matches.size() > 1 && isBlank(memberCode)) throw new IllegalArgumentException("Member code is required for duplicate names"); String normalizedCode = isBlank(memberCode) ? null : memberCode.trim().toUpperCase(Locale.ROOT); return matches.stream().filter(m -> normalizedCode == null || normalizedCode.equals(m.memberCode)).findFirst().orElseThrow(() -> new IllegalArgumentException("No matching member found")); }
  private boolean isBlank(String value) { return value == null || value.isBlank(); }
  private void recordAudit(String action, String memberId) { if (audit != null) audit.record("anonymous", action, "member", memberId, Map.of(), Map.of()); }
  private ApiDtos.PublicPaymentIntentResponse toPublicResponse(PaymentIntentDocument intent) { ApiDtos.SettingsResponse current = currentSettings(); String url = properties.getQrImageBaseUrl() + "/" + current.bankCode() + "-" + current.accountNumber() + "-compact2.png?amount=" + intent.totalAmount + "&addInfo=" + URLEncoder.encode(intent.transferContent, StandardCharsets.UTF_8) + "&accountName=" + URLEncoder.encode(current.accountHolder(), StandardCharsets.UTF_8); return new ApiDtos.PublicPaymentIntentResponse(intent.id, intent.totalAmount, intent.transferContent, url, current.accountHolder(), current.accountNumber(), current.bankName(), intent.expiresAt, intent.status); }
  private ApiDtos.SettingsResponse currentSettings() { if (settings != null) return settings.get(); return new ApiDtos.SettingsResponse(properties.getDefaultContributionAmount(), properties.getAutoReconciliationThreshold(), properties.getBankName(), properties.getBankCode(), properties.getAccountNumber(), properties.getAccountHolder(), properties.isDueSchedulerEnabled(), properties.isStartupCatchUpEnabled(), properties.isIngestionEnabled(), properties.isGmailEnabled(), properties.getGmailSenderFilter(), properties.isSmtpEnabled(), properties.getSmtpHost(), properties.getSmtpPort(), properties.getSmtpFrom()); }
  private void parseMonth(String month) { try { YearMonth.parse(month); } catch (RuntimeException ex) { throw new IllegalArgumentException("Month must be YYYY-MM"); } }
}
