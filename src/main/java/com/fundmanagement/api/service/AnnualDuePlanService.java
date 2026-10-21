package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ContributionPeriod;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.repository.DuePeriodRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Materializes plan rows only. Never rewrites existing dues or allocates money. */
@Service
public class AnnualDuePlanService {
  private final DuePeriodRepository dues;
  private final MemberService members;
  private final AuditService audit;

  public AnnualDuePlanService(DuePeriodRepository dues, MemberService members) {
    this(dues, members, null);
  }

  @Autowired
  public AnnualDuePlanService(DuePeriodRepository dues, MemberService members, AuditService audit) {
    this.dues = dues; this.members = members; this.audit = audit;
  }

  public List<DuePeriodDocument> missing(MemberDocument member, int year, List<DuePeriodDocument> existing) {
    if (member.status != com.fundmanagement.api.domain.MemberStatus.ACTIVE) {
      throw new IllegalStateException("Only active members may materialize annual dues");
    }
    List<String> periods = ContributionPeriod.yearPeriods(year);
    String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank()
        ? member.baselineMonth : member.contributionStartMonth;
    ContributionPeriod.validateRegularMonth(start);
    var known = new HashSet<>(existing.stream().map(due -> due.month).toList());
    List<DuePeriodDocument> result = new ArrayList<>();
    for (String period : periods) {
      if (!ContributionPeriod.inPlan(period, start, 14) || known.contains(period)) continue;
      DuePeriodDocument due = new DuePeriodDocument();
      due.memberId = member.id; due.month = period; due.amountDue = members.rateFor(member, period);
      due.seededPaid = member.initiallyPaidMonths != null && member.initiallyPaidMonths.contains(period);
      due.amountPaid = due.seededPaid ? due.amountDue : 0;
      due.status = due.seededPaid ? DueStatus.PAID : DueStatus.UNPAID;
      result.add(due);
    }
    return result;
  }

  public List<String> ensureYear(MemberDocument member, int year, String actor) {
    List<DuePeriodDocument> existing = dues.findPlanByMemberIdAndMonthBetween(member.id,
        ContributionPeriod.key(year, 1), ContributionPeriod.key(year, 14));
    List<String> created = new ArrayList<>();
    for (DuePeriodDocument due : missing(member, year, existing)) {
      dues.insert(due); // Unique member/month index + enclosing transaction handle races.
      created.add(due.month);
      if (audit != null) audit.record(actor, "DUE_PERIOD_CREATED", "due_period", due.id,
          Map.of(), Map.of("memberId", member.id, "month", due.month,
              "amountDue", due.amountDue, "seededPaid", due.seededPaid));
    }
    return List.copyOf(created);
  }
}
