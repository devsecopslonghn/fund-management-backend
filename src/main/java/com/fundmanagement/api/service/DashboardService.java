package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ExpenseDocument;
import com.fundmanagement.api.domain.ManualPaymentDocument;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.ExpenseRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.repository.BankTransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final ManualPaymentRepository payments; private final ExpenseRepository expenses; private final MemberRepository members; private final DuePeriodRepository dues; private final MemberService memberService; private final BankTransactionRepository transactions;
  public DashboardService(ManualPaymentRepository payments, ExpenseRepository expenses, MemberRepository members, DuePeriodRepository dues, MemberService memberService, BankTransactionRepository transactions) { this.payments = payments; this.expenses = expenses; this.members = members; this.dues = dues; this.memberService = memberService; this.transactions = transactions; }
  public ApiDtos.PublicOverviewResponse publicOverview() { Totals totals = totals(); YearMonth now = YearMonth.now(); MonthlyTotals current = monthly(now); List<ApiDtos.PublicMemberStatus> status = members.findByStatus(MemberStatus.ACTIVE).stream().map(m -> new ApiDtos.PublicMemberStatus(m.fullName, publicStatus(m))).toList(); return new ApiDtos.PublicOverviewResponse(totals.income - totals.expense, current.income, current.expense, compliance(now), series(now), status); }
  /** Public payment dashboard deliberately includes detailed member dues under the approved product policy. */
  public ApiDtos.PublicPaymentDashboardResponse publicPaymentDashboard() {
    ApiDtos.PublicOverviewResponse overview = publicOverview();
    List<ApiDtos.PublicPaymentDashboardMember> dashboardMembers = members.findByStatus(MemberStatus.ACTIVE).stream()
        .sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName))
        .map(member -> new ApiDtos.PublicPaymentDashboardMember(
            member.id,
            member.fullName,
            member.memberCode,
            member.joinMonth,
            member.baselineMonth,
            member.contributionRates.stream().max(Comparator.comparing(rate -> rate.effectiveFrom)).map(rate -> rate.amount).orElse(0L),
            publicStatus(member),
            dues.findByMemberIdOrderByMonthAsc(member.id).stream()
                .sorted(Comparator.comparing(due -> due.month == null ? "" : due.month))
                .map(due -> new ApiDtos.PublicPaymentDashboardDue(due.id, due.month, due.amountDue, due.amountPaid, Math.max(0, due.amountDue - due.amountPaid), due.status))
                .toList()))
        .toList();
    return new ApiDtos.PublicPaymentDashboardResponse(overview.balance(), overview.incomeThisMonth(), overview.expenseThisMonth(), overview.monthlySeries(), dashboardMembers);
  }
  public ApiDtos.DashboardResponse adminOverview() { Totals totals = totals(); YearMonth now = YearMonth.now(); List<MemberDocument> late = members.findByStatus(MemberStatus.ACTIVE).stream().filter(m -> { String status = publicStatus(m); return "SOME_MONTHS_MISSING".equals(status) || "OVERDUE".equals(status); }).sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName)).toList(); long reviewCount = transactions.findByReconciliationStatus("NEEDS_REVIEW").size(); return new ApiDtos.DashboardResponse(totals.income - totals.expense, totals.income, totals.expense, compliance(now), reviewCount, series(now), late.stream().map(memberService::toResponse).toList()); }
  private Totals totals() { return new Totals(payments.findAll().stream().mapToLong(p -> p.amount).sum(), expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(LocalDate.of(2000, 1, 1), LocalDate.now().plusYears(1)).stream().mapToLong(e -> e.amount).sum()); }
  private MonthlyTotals monthly(YearMonth month) { Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC); Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC); long income = payments.findByOccurredAtBetween(from, to).stream().mapToLong(p -> p.amount).sum(); long expense = expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(month.atDay(1), month.atEndOfMonth()).stream().mapToLong(e -> e.amount).sum(); return new MonthlyTotals(income, expense); }
  private List<ApiDtos.MonthlySeriesResponse> series(YearMonth end) { List<ApiDtos.MonthlySeriesResponse> result = new ArrayList<>(); for (int i = 5; i >= 0; i--) { YearMonth month = end.minusMonths(i); MonthlyTotals values = monthly(month); result.add(new ApiDtos.MonthlySeriesResponse(month.toString(), values.income, values.expense)); } return result; }
  private int compliance(YearMonth month) { List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE); if (active.isEmpty()) return 100; long paid = active.stream().filter(m -> dues.findByMemberIdAndMonth(m.id, month.toString()).map(d -> d.status == DueStatus.PAID || d.status == DueStatus.OVERPAID).orElse(false)).count(); return (int) Math.round(paid * 100.0 / active.size()); }
  private String publicStatus(MemberDocument member) { List<DuePeriodDocument> all = dues.findByMemberIdOrderByMonthAsc(member.id); if (all.isEmpty()) return "NO_DUE_PERIODS"; List<DuePeriodDocument> outstanding = all.stream().filter(d -> d.amountPaid < d.amountDue).toList(); if (outstanding.isEmpty()) return "UP_TO_DATE"; return outstanding.size() == 1 ? "SOME_MONTHS_MISSING" : "OVERDUE"; }
  private record Totals(long income, long expense) {} private record MonthlyTotals(long income, long expense) {}
}
