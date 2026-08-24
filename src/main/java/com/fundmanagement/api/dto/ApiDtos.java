package com.fundmanagement.api.dto;

import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.MemberStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
  private ApiDtos() {}
  public record MemberUpsertRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String memberCode, @NotBlank @Size(max = 120) String fullName, @NotBlank @Email String email, @Size(max = 32) String phone, @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String joinMonth, @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String baselineMonth, @Min(1) long contributionAmount, MemberStatus status, @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String contributionStartMonth, @Min(1) @jakarta.validation.constraints.Max(12) Integer annualContributionMonths, List<@Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String> initiallyPaidMonths) {
    public MemberUpsertRequest(String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, long contributionAmount, MemberStatus status) { this(memberCode, fullName, email, phone, joinMonth, baselineMonth, contributionAmount, status, null, null, List.of()); }
  }
  public record MemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount, String contributionStartMonth, Integer annualContributionMonths, List<String> initiallyPaidMonths) {
    public MemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount) { this(id, memberCode, fullName, email, phone, joinMonth, baselineMonth, status, contributionAmount, baselineMonth, 12, List.of()); }
  }
  public record CreatedMemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount, String contributionStartMonth, Integer annualContributionMonths, List<String> initiallyPaidMonths) {
    public CreatedMemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount) { this(id, memberCode, fullName, email, phone, joinMonth, baselineMonth, status, contributionAmount, baselineMonth, 12, List.of()); }
  }
  public record MemberPageResponse(List<MemberResponse> content, int page, int size, long totalElements) {}
  public record DuePeriodResponse(String id, String month, long amountDue, long amountPaid, DueStatus status, String notes) {}
  public record AdminDuePeriodResponse(String id, String memberId, String month, long amountDue, long amountPaid, DueStatus status, String notes, Long version) {}
  public record DuePeriodPageResponse(List<AdminDuePeriodResponse> content, int page, int size, long totalElements) {}
  public record DueAdjustmentRequest(@Min(0) long amountDue, @Size(max = 1000) String notes) {}
  /** Public response deliberately contains only the outstanding amount, never paid totals or history. */
  public record PublicDuePeriodResponse(String id, String month, long outstandingAmount) {}
  public record PublicMemberMatch(String fullName, String memberCode) {}
  public record PublicPaymentOptionsResponse(boolean requiresMemberCode, List<PublicMemberMatch> matches, List<PublicDuePeriodResponse> duePeriods) {}
  public record PublicPaymentOptionsRequest(@NotBlank @Size(max = 120) String fullName, @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String memberCode) {}
  public record PublicPaymentIntentRequest(@NotBlank @Size(max = 120) String fullName, @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String memberCode, List<@NotBlank String> duePeriodIds, @Pattern(regexp = "^(EXPLICIT|ALL_OUTSTANDING|MONTH_COUNT|THROUGH_MONTH)$") String selectionMode, @Min(1) @jakarta.validation.constraints.Max(120) Integer monthCount, @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String throughMonth) {
    public PublicPaymentIntentRequest(String fullName, String memberCode, List<String> duePeriodIds) { this(fullName, memberCode, duePeriodIds, "EXPLICIT", null, null); }
  }
  /** Public payment response includes fund transfer details but never member identity or history. */
  public record PublicPaymentIntentResponse(String id, long amount, String transferContent, String qrImageUrl, String accountHolder, String accountNumber, String bankName, Instant expiresAt, IntentStatus status, List<PaymentPlanPeriod> plan) {
    public PublicPaymentIntentResponse(String id, long amount, String transferContent, String qrImageUrl, String accountHolder, String accountNumber, String bankName, Instant expiresAt, IntentStatus status) { this(id, amount, transferContent, qrImageUrl, accountHolder, accountNumber, bankName, expiresAt, status, List.of()); }
  }
  public record PaymentPlanPeriod(String duePeriodId, String month, long amountDue, long amountPaid, long amountToPay) {}
  /** Polling response intentionally contains no member, amount, account, or transaction history. */
  public record PublicPaymentIntentStatusResponse(String id, IntentStatus status, Instant expiresAt) {}
  /** Shareable payment details intentionally exclude payer identity and internal transaction data. */
  public record PublicPaymentIntentDetailsResponse(String id, long amount, String transferContent, String qrImageUrl, String accountHolder, String accountNumber, String bankName, Instant expiresAt, IntentStatus status) {}
  public record PaymentIntentResponse(String id, long amount, String transferContent, String qrImageUrl, String accountHolder, String accountNumber, String bankName, Instant expiresAt, IntentStatus status) {}
  public record GenerationResult(String month, int created, int skipped) {}
  public record YearGenerationResult(int year, int created, int skipped, int initiallyPaid) {}
  public record ManualPaymentRequest(@NotBlank String memberId, @Min(1) long amount, @NotNull Instant occurredAt, @NotBlank @Size(min = 16, max = 128) String idempotencyKey, @Size(max = 500) String note) {}
  public record AllocationResponse(String duePeriodId, long amount) {}
  public record ManualPaymentResponse(String id, String memberId, long amount, long unallocatedAmount, List<AllocationResponse> allocations) {}
  public record ExpenseRequest(@NotNull LocalDate date, @Min(1) long amount, @NotBlank @Size(max = 500) String purpose, @Size(max = 64) String category, @Size(max = 120) String requestedBy, @Size(max = 1000) String notes) {}
  public record ExpenseResponse(String id, LocalDate date, long amount, String purpose, String category, String requestedBy, String notes, Instant createdAt, Instant updatedAt) {}
  public record PublicMemberStatus(String name, String status) {}
  public record MonthlySeriesResponse(String month, long income, long expense) {}
  public record PublicOverviewResponse(long balance, long incomeThisMonth, long expenseThisMonth, int currentMonthCompliancePercent, List<MonthlySeriesResponse> monthlySeries, List<PublicMemberStatus> members) {}
  /** Public payment dashboard data. This endpoint intentionally exposes member dues per the approved product policy. */
  public record PublicPaymentDashboardResponse(long balance, long incomeThisMonth, long expenseThisMonth, List<MonthlySeriesResponse> monthlySeries, List<PublicPaymentDashboardMember> members, Integer year, List<String> months) {
    public PublicPaymentDashboardResponse(long balance, long incomeThisMonth, long expenseThisMonth, List<MonthlySeriesResponse> monthlySeries, List<PublicPaymentDashboardMember> members) { this(balance, incomeThisMonth, expenseThisMonth, monthlySeries, members, null, List.of()); }
  }
  public record PublicPaymentDashboardMember(String id, String fullName, String memberCode, String joinMonth, String baselineMonth, long contributionAmount, String status, List<PublicPaymentDashboardDue> duePeriods) {}
  public record PublicPaymentDashboardDue(String id, String month, long amountDue, long amountPaid, long outstandingAmount, DueStatus status, String displayStatus) {
    public PublicPaymentDashboardDue(String id, String month, long amountDue, long amountPaid, long outstandingAmount, DueStatus status) { this(id, month, amountDue, amountPaid, outstandingAmount, status, status == null ? "NOT_OPENED" : status.name()); }
  }
  /** Lightweight year board contract. It intentionally mirrors the legacy response so clients can migrate without a data-shape fork. */
  public record PublicContributionBoardResponse(long balance, long incomeThisMonth, long expenseThisMonth, List<MonthlySeriesResponse> monthlySeries, List<PublicPaymentDashboardMember> members, int year, List<String> months) {}
  public record DashboardResponse(long balance, long totalIncome, long totalExpense, int currentMonthCompliancePercent, long reviewCount, List<MonthlySeriesResponse> monthlySeries, List<MemberResponse> latePayers) {}
  public record SettingsResponse(long defaultContributionAmount, double autoReconciliationThreshold, String bankName, String bankCode, String accountNumber, String accountHolder, boolean dueSchedulerEnabled, boolean startupCatchUpEnabled, boolean ingestionEnabled, boolean gmailEnabled, String gmailSenderFilter, boolean smtpEnabled, String smtpHost, int smtpPort, String smtpFrom) {}
  public record SettingsRequest(@Min(1) long defaultContributionAmount, @Min(0) @Max(1) double autoReconciliationThreshold, @NotBlank String bankName, @NotBlank String bankCode, @NotBlank String accountNumber, @NotBlank String accountHolder, boolean dueSchedulerEnabled, boolean startupCatchUpEnabled, boolean ingestionEnabled, boolean gmailEnabled, @Size(max = 254) String gmailSenderFilter, boolean smtpEnabled, @Size(max = 255) String smtpHost, @Min(1) int smtpPort, @Size(max = 254) String smtpFrom, @Size(max = 255) String smtpSecretRef) {}
  public record AuditLogResponse(String id, String actor, String action, String entityType, String entityId, Map<String, Object> before, Map<String, Object> after, Instant timestamp) {}
  public record AuditLogPageResponse(List<AuditLogResponse> content, int page, int size, long totalElements) {}
  public record ExpenseReportResponse(long totalExpense, Map<String, Long> byCategory, List<MonthlySeriesResponse> monthlySeries) {}
  public record BankTransactionResponse(String id, String providerMessageId, Instant occurredAt, long amount, String direction, String parsedContent, Long availableBalance, String reconciliationStatus, String matchedMemberId, List<String> matchedDuePeriodIds, Instant createdAt) {}
  public record ReconciliationPageResponse(List<BankTransactionResponse> content, int page, int size, long totalElements) {}
  public record BankTransactionIngestionRequest(@NotBlank @Size(max = 255) String providerMessageId, @NotBlank @Size(max = 500000) String rawPayload) {}
  public record ReconciliationReviewRequest(@NotNull Boolean ignore, String memberId, @Size(max = 500) String note) {}
  public record IngestionStatusResponse(boolean enabled, boolean gmailEnabled, boolean smtpEnabled, String senderFilter, String mode) {}
  public record LegacyMigrationPreviewResponse(boolean dryRun, boolean applyEnabled, long memberCount, long duePeriodCount, long paymentCount, String message) {}
  public record GmailOAuthStartResponse(String authorizationUrl, Instant expiresAt) {}
  public record GmailOAuthCallbackResponse(boolean connected, String message) {}
  public record GmailOAuthStatusResponse(boolean configured, boolean connected, String monitoredMailbox, String senderFilter, Instant connectedAt, String mode) {}
}
