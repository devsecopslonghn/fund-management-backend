package com.fundmanagement.api.dto;

import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.MemberStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ApiDtos {
  private ApiDtos() {}
  public record MemberUpsertRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String memberCode, @NotBlank @Size(max = 120) String fullName, @NotBlank @Email String email, @Size(max = 32) String phone, @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String joinMonth, @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String baselineMonth, @Min(1) long contributionAmount, MemberStatus status) {}
  public record MemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount) {}
  public record CreatedMemberResponse(String id, String memberCode, String fullName, String email, String phone, String joinMonth, String baselineMonth, MemberStatus status, long contributionAmount, String magicLink) {}
  public record MagicLinkResponse(String magicLink) {}
  public record MemberPageResponse(List<MemberResponse> content, int page, int size, long totalElements) {}
  public record DuePeriodResponse(String id, String month, long amountDue, long amountPaid, DueStatus status, String notes) {}
  public record PersonalMemberResponse(String id, String fullName, String memberCode) {}
  public record PersonalOverviewResponse(PersonalMemberResponse member, long totalPaid, long totalOwed, List<DuePeriodResponse> duePeriods) {}
  public record CreatePaymentIntentRequest(@NotEmpty List<@NotBlank String> duePeriodIds) {}
  public record PaymentIntentResponse(String id, long amount, String transferContent, String qrImageUrl, String accountHolder, String accountNumber, String bankName, Instant expiresAt, IntentStatus status) {}
  public record GenerationResult(String month, int created, int skipped) {}
  public record ManualPaymentRequest(@NotBlank String memberId, @Min(1) long amount, @NotNull Instant occurredAt, @NotBlank @Size(min = 16, max = 128) String idempotencyKey, @Size(max = 500) String note) {}
  public record AllocationResponse(String duePeriodId, long amount) {}
  public record ManualPaymentResponse(String id, String memberId, long amount, long unallocatedAmount, List<AllocationResponse> allocations) {}
  public record ExpenseRequest(@NotNull LocalDate date, @Min(1) long amount, @NotBlank @Size(max = 500) String purpose, @Size(max = 64) String category, @Size(max = 120) String requestedBy, @Size(max = 1000) String notes) {}
  public record ExpenseResponse(String id, LocalDate date, long amount, String purpose, String category, String requestedBy, String notes, Instant createdAt, Instant updatedAt) {}
  public record PublicMemberStatus(String name, String status) {}
  public record MonthlySeriesResponse(String month, long income, long expense) {}
  public record PublicOverviewResponse(long balance, long incomeThisMonth, long expenseThisMonth, int currentMonthCompliancePercent, List<MonthlySeriesResponse> monthlySeries, List<PublicMemberStatus> members) {}
  public record DashboardResponse(long balance, long totalIncome, long totalExpense, int currentMonthCompliancePercent, long reviewCount, List<MonthlySeriesResponse> monthlySeries, List<MemberResponse> latePayers) {}
  public record PersonalLinkRequest(@NotBlank @Email String email) {}
  public record AcceptedResponse(String message) {}
  public record SettingsResponse(long defaultContributionAmount, double autoReconciliationThreshold, String bankName, String bankCode, String accountNumber, String accountHolder) {}
  public record SettingsRequest(@Min(1) long defaultContributionAmount, @Min(0) double autoReconciliationThreshold, @NotBlank String bankName, @NotBlank String bankCode, @NotBlank String accountNumber, @NotBlank String accountHolder) {}
}
