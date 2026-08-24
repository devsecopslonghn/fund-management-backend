package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ContributionPeriod;
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
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.bson.Document;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final ManualPaymentRepository payments; private final ExpenseRepository expenses; private final MemberRepository members; private final DuePeriodRepository dues; private final MemberService memberService; private final BankTransactionRepository transactions; private final MongoTemplate mongo;
  public DashboardService(ManualPaymentRepository payments, ExpenseRepository expenses, MemberRepository members, DuePeriodRepository dues, MemberService memberService, BankTransactionRepository transactions) { this(payments, expenses, members, dues, memberService, transactions, null); }
  @Autowired public DashboardService(ManualPaymentRepository payments, ExpenseRepository expenses, MemberRepository members, DuePeriodRepository dues, MemberService memberService, BankTransactionRepository transactions, MongoTemplate mongo) { this.payments = payments; this.expenses = expenses; this.members = members; this.dues = dues; this.memberService = memberService; this.transactions = transactions; this.mongo = mongo; }
  public ApiDtos.PublicOverviewResponse publicOverview() { List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE); Map<String, List<DuePeriodDocument>> dueByMember = dueByMember(active); return overview(active, dueByMember); }
  /** Public payment dashboard deliberately includes detailed member dues under the approved product policy. */
  public ApiDtos.PublicPaymentDashboardResponse publicPaymentDashboard() {
    List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE); Map<String, List<DuePeriodDocument>> dueByMember = dueByMember(active); ApiDtos.PublicOverviewResponse overview = overview(active, dueByMember);
    List<ApiDtos.PublicPaymentDashboardMember> dashboardMembers = active.stream()
        .sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName))
        .map(member -> {
          List<DuePeriodDocument> memberDues = dueByMember.getOrDefault(member.id, List.of());
          return new ApiDtos.PublicPaymentDashboardMember(
            member.id,
            member.fullName,
            member.memberCode,
            member.joinMonth,
            member.baselineMonth,
            member.contributionRates.stream().max(Comparator.comparing(rate -> rate.effectiveFrom)).map(rate -> rate.amount).orElse(0L),
            publicStatus(memberDues),
            memberDues.stream()
                .sorted(Comparator.comparing(due -> due.month == null ? "" : due.month))
                .map(due -> new ApiDtos.PublicPaymentDashboardDue(due.id, due.month, due.amountDue, due.amountPaid, Math.max(0, due.amountDue - due.amountPaid), due.status))
                .toList());
        })
        .toList();
    return new ApiDtos.PublicPaymentDashboardResponse(overview.balance(), overview.incomeThisMonth(), overview.expenseThisMonth(), overview.monthlySeries(), dashboardMembers);
  }
  /** Year board used by the home page. Missing configured periods are returned as NOT_OPENED, not as debt. */
  public ApiDtos.PublicPaymentDashboardResponse publicPaymentDashboard(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE); Map<String, List<DuePeriodDocument>> dueByMember = dueByMember(active); ApiDtos.PublicOverviewResponse overview = overview(active, dueByMember);
    List<String> months = ContributionPeriod.yearPeriods(year);
    List<ApiDtos.PublicPaymentDashboardMember> result = active.stream().sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName)).map(member -> {
      var existing = dueByMember.getOrDefault(member.id, List.of()).stream().collect(Collectors.toMap(d -> d.month, d -> d, (a, b) -> a));
      String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
      int count = 14;
      YearMonth from = YearMonth.parse(start);
      List<ApiDtos.PublicPaymentDashboardDue> board = months.stream().map(month -> {
        if (!ContributionPeriod.inPlan(month, start, count)) return new ApiDtos.PublicPaymentDashboardDue(null, month, 0, 0, 0, null, "NOT_OPENED");
        DuePeriodDocument due = existing.get(month);
        if (due != null) return new ApiDtos.PublicPaymentDashboardDue(due.id, month, due.amountDue, due.amountPaid, Math.max(0, due.amountDue - due.amountPaid), due.status);
        long amount = memberService.rateFor(member, month); return new ApiDtos.PublicPaymentDashboardDue(null, month, amount, 0, amount, DueStatus.UNPAID, "UNPAID");
      }).toList();
      return new ApiDtos.PublicPaymentDashboardMember(member.id, member.fullName, member.memberCode, member.joinMonth, member.baselineMonth, member.contributionRates.stream().max(Comparator.comparing(rate -> rate.effectiveFrom)).map(rate -> rate.amount).orElse(0L), publicStatus(member, dueByMember.getOrDefault(member.id, List.of())), board);
    }).toList();
    return new ApiDtos.PublicPaymentDashboardResponse(overview.balance(), overview.incomeThisMonth(), overview.expenseThisMonth(), overview.monthlySeries(), result, year, months);
  }
  /**
   * Bounded read model for the public home board. The legacy dashboard remains
   * available, while this endpoint only reads the requested year's projected
   * due fields and uses aggregation for ledger totals.
   */
  public ApiDtos.PublicContributionBoardResponse publicContributionBoard(int year) {
    if (year < 2000 || year > 9999) throw new IllegalArgumentException("Year is invalid");
    List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE);
    List<String> months = ContributionPeriod.yearPeriods(year);
    if (active.isEmpty()) { MonthlyTotals current = monthly(YearMonth.now()); return new ApiDtos.PublicContributionBoardResponse(ledgerBalance(), current.income, current.expense, series(YearMonth.now()), List.of(), year, months); }
    List<String> ids = active.stream().map(m -> m.id).toList();
    List<DuePeriodDocument> due = dues.findBoardByMemberIdInAndMonthBetweenOrderByMonthAsc(ids, months.getFirst(), months.getLast());
    Map<String, List<DuePeriodDocument>> byMember = due.stream().collect(Collectors.groupingBy(d -> d.memberId, HashMap::new, Collectors.toList()));
    List<ApiDtos.PublicPaymentDashboardMember> result = active.stream().sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName)).map(member -> {
      Map<String, DuePeriodDocument> existing = byMember.getOrDefault(member.id, List.of()).stream().collect(Collectors.toMap(d -> d.month, d -> d, (a, b) -> a));
      String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
      int count = 14;
      List<ApiDtos.PublicPaymentDashboardDue> board = months.stream().map(month -> {
        if (!ContributionPeriod.inPlan(month, start, count)) return new ApiDtos.PublicPaymentDashboardDue(null, month, 0, 0, 0, null, "NOT_OPENED");
        DuePeriodDocument d = existing.get(month);
        if (d != null) return new ApiDtos.PublicPaymentDashboardDue(d.id, month, d.amountDue, d.amountPaid, Math.max(0, d.amountDue - d.amountPaid), d.status);
        long amount = memberService.rateFor(member, month); return new ApiDtos.PublicPaymentDashboardDue(null, month, amount, 0, amount, DueStatus.UNPAID, "UNPAID");
      }).toList();
      return new ApiDtos.PublicPaymentDashboardMember(member.id, member.fullName, member.memberCode, member.joinMonth, member.baselineMonth, latestRate(member), publicStatus(member, byMember.getOrDefault(member.id, List.of())), board);
    }).toList();
    YearMonth now = YearMonth.now(); MonthlyTotals current = monthly(now);
    return new ApiDtos.PublicContributionBoardResponse(ledgerBalance(), current.income, current.expense, series(now), result, year, months);
  }
  public ApiDtos.DashboardResponse adminOverview() { Totals totals = totals(); YearMonth now = YearMonth.now(); List<MemberDocument> late = members.findByStatus(MemberStatus.ACTIVE).stream().filter(m -> { String status = publicStatus(m); return "SOME_MONTHS_MISSING".equals(status) || "OVERDUE".equals(status); }).sorted(Comparator.comparing(m -> m.fullName == null ? "" : m.fullName)).toList(); long reviewCount = transactions.findByReconciliationStatus("NEEDS_REVIEW").size(); return new ApiDtos.DashboardResponse(totals.income - totals.expense, totals.income, totals.expense, compliance(now), reviewCount, series(now), late.stream().map(memberService::toResponse).toList()); }
  private Criteria activePaymentCriteria() { return new Criteria().orOperator(Criteria.where("reversed").ne(true), Criteria.where("reversed").exists(false)); }
  private Totals totals() { if (mongo != null) return new Totals(aggregateSum("manual_payments", activePaymentCriteria(), "amount"), aggregateSum("expenses", Criteria.where("deleted").is(false), "amount")); return new Totals(payments.findAll().stream().filter(p -> !p.reversed).mapToLong(p -> p.amount).sum(), expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(LocalDate.of(2000, 1, 1), LocalDate.now().plusYears(1)).stream().mapToLong(e -> e.amount).sum()); }
  private MonthlyTotals monthly(YearMonth month) { Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC); Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC); if (mongo != null) return new MonthlyTotals(aggregateSum("manual_payments", new Criteria().andOperator(activePaymentCriteria(), Criteria.where("occurredAt").gte(from).lt(to)), "amount"), aggregateSum("expenses", Criteria.where("deleted").is(false).and("date").gte(month.atDay(1).toString()).lt(month.plusMonths(1).atDay(1).toString()), "amount")); long income = payments.findByOccurredAtBetween(from, to).stream().filter(p -> !p.reversed).mapToLong(p -> p.amount).sum(); long expense = expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(month.atDay(1), month.atEndOfMonth()).stream().mapToLong(e -> e.amount).sum(); return new MonthlyTotals(income, expense); }
  private List<ApiDtos.MonthlySeriesResponse> series(YearMonth end) { List<ApiDtos.MonthlySeriesResponse> result = new ArrayList<>(); for (int i = 5; i >= 0; i--) { YearMonth month = end.minusMonths(i); MonthlyTotals values = monthly(month); result.add(new ApiDtos.MonthlySeriesResponse(month.toString(), values.income, values.expense)); } return result; }
  private int compliance(YearMonth month) { List<MemberDocument> active = members.findByStatus(MemberStatus.ACTIVE); if (active.isEmpty()) return 100; long paid = active.stream().filter(m -> dues.findByMemberIdAndMonth(m.id, month.toString()).map(d -> d.status == DueStatus.PAID || d.status == DueStatus.OVERPAID).orElse(false)).count(); return (int) Math.round(paid * 100.0 / active.size()); }
  private ApiDtos.PublicOverviewResponse overview(List<MemberDocument> active, Map<String, List<DuePeriodDocument>> dueByMember) { Totals totals = totals(); YearMonth now = YearMonth.now(); MonthlyTotals current = monthly(now); long paid = active.stream().filter(m -> dueByMember.getOrDefault(m.id, List.of()).stream().anyMatch(d -> now.toString().equals(d.month) && (d.status == DueStatus.PAID || d.status == DueStatus.OVERPAID))).count(); int compliance = active.isEmpty() ? 100 : (int) Math.round(paid * 100.0 / active.size()); List<ApiDtos.PublicMemberStatus> status = active.stream().map(m -> new ApiDtos.PublicMemberStatus(m.fullName, publicStatus(dueByMember.getOrDefault(m.id, List.of())))).toList(); return new ApiDtos.PublicOverviewResponse(totals.income - totals.expense, current.income, current.expense, compliance, series(now), status); }
  private Map<String, List<DuePeriodDocument>> dueByMember(List<MemberDocument> active) { if (active.isEmpty()) return Map.of(); List<String> ids = active.stream().map(m -> m.id).toList(); return dues.findByMemberIdInOrderByMonthAsc(ids).stream().collect(Collectors.groupingBy(d -> d.memberId, HashMap::new, Collectors.toList())); }
  private String publicStatus(MemberDocument member) { return publicStatus(member, dues.findByMemberIdOrderByMonthAsc(member.id)); }
  private String publicStatus(MemberDocument member, List<DuePeriodDocument> all) { return publicStatus(all.stream().filter(due -> inMemberPlan(member, due.month)).toList()); }
  private boolean inMemberPlan(MemberDocument member, String period) {
    String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank() ? member.baselineMonth : member.contributionStartMonth;
    return start != null && ContributionPeriod.isRegularMonth(start) && period != null && ContributionPeriod.isValid(period) && ContributionPeriod.inPlan(period, start, 14);
  }
  private String publicStatus(List<DuePeriodDocument> all) { if (all.isEmpty()) return "NO_DUE_PERIODS"; long outstanding = all.stream().filter(d -> d.amountPaid < d.amountDue).count(); if (outstanding == 0) return "UP_TO_DATE"; return outstanding == 1 ? "SOME_MONTHS_MISSING" : "OVERDUE"; }
  private long ledgerBalance() { Totals totals = totals(); return totals.income - totals.expense; }
  private MonthlyTotals ledgerMonth(int year, int month) { return monthly(YearMonth.of(year, month)); }
  private long latestRate(MemberDocument member) { return member.contributionRates == null ? 0L : member.contributionRates.stream().max(Comparator.comparing(rate -> rate.effectiveFrom)).map(rate -> rate.amount).orElse(0L); }
  private long aggregateSum(String collection, Criteria criteria, String field) { Aggregation pipeline = criteria.getCriteriaObject().isEmpty() ? Aggregation.newAggregation(Aggregation.group().sum(field).as("total")) : Aggregation.newAggregation(Aggregation.match(criteria), Aggregation.group().sum(field).as("total")); Document result = mongo.aggregate(pipeline, collection, Document.class).getUniqueMappedResult(); if (result == null) return 0L; Number total = result.get("total", Number.class); return total == null ? 0L : total.longValue(); }
  private record Totals(long income, long expense) {} private record MonthlyTotals(long income, long expense) {}
}
