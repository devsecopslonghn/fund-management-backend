package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.domain.ContributionRate;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.ExpenseRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import com.fundmanagement.api.repository.MemberRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
  @Mock ManualPaymentRepository payments;
  @Mock ExpenseRepository expenses;
  @Mock MemberRepository members;
  @Mock DuePeriodRepository dues;
  @Mock MemberService memberService;
  @Mock BankTransactionRepository transactions;

  @Test void publicPaymentDashboardSortsMembersAndReturnsDetailedDueAmountsWithoutContacts() {
    MemberDocument beta = member("m-2", "Beta", "B002");
    MemberDocument alpha = member("m-1", "Alpha", "A001");
    DuePeriodDocument later = due("due-2", "2026-09", 200_000, 100_000);
    DuePeriodDocument earlier = due("due-1", "2026-08", 100_000, 0);

    when(payments.findAll()).thenReturn(List.of());
    when(payments.findByOccurredAtBetween(any(), any())).thenReturn(List.of());
    when(expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(any(), any())).thenReturn(List.of());
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(beta, alpha));
    when(dues.findByMemberIdAndMonth(anyString(), anyString())).thenReturn(Optional.empty());
    when(dues.findByMemberIdOrderByMonthAsc("m-1")).thenReturn(List.of(later, earlier));
    when(dues.findByMemberIdOrderByMonthAsc("m-2")).thenReturn(List.of());

    ApiDtos.PublicPaymentDashboardResponse response = new DashboardService(payments, expenses, members, dues, memberService, transactions).publicPaymentDashboard();

    assertThat(response.members()).extracting(ApiDtos.PublicPaymentDashboardMember::fullName).containsExactly("Alpha", "Beta");
    assertThat(response.members().getFirst().duePeriods()).extracting(ApiDtos.PublicPaymentDashboardDue::month).containsExactly("2026-08", "2026-09");
    assertThat(response.members().getFirst().duePeriods().getFirst().outstandingAmount()).isEqualTo(100_000);
    assertThat(response.members().getFirst().memberCode()).isEqualTo("A001");
  }

  private MemberDocument member(String id, String name, String code) {
    MemberDocument member = new MemberDocument();
    member.id = id; member.fullName = name; member.memberCode = code; member.status = MemberStatus.ACTIVE;
    member.contributionRates = new java.util.ArrayList<>(List.of(new ContributionRate(100_000, "2026-01", null)));
    return member;
  }

  private DuePeriodDocument due(String id, String month, long amountDue, long amountPaid) {
    DuePeriodDocument due = new DuePeriodDocument();
    due.id = id; due.month = month; due.amountDue = amountDue; due.amountPaid = amountPaid; due.status = amountPaid == 0 ? DueStatus.UNPAID : DueStatus.PARTIAL;
    return due;
  }
}
