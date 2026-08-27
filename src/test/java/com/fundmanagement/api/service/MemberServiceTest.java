package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.domain.ContributionRate;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.MemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {
  @Mock MemberRepository members;
  @Mock AuditService audit;

  @Test
  void appliesContributionIncreaseFromRequestedMonthAndKeepsRateHistory() {
    MemberDocument member = memberWithInitialRate();
    when(members.findById("member-1")).thenReturn(Optional.of(member));

    MemberService service = new MemberService(members, audit);
    service.update("member-1", request(200_000, "2026-07"), "admin");

    assertThat(member.contributionRates).extracting(rate -> rate.amount)
        .containsExactly(100_000L, 200_000L);
    assertThat(member.contributionRates).extracting(rate -> rate.effectiveFrom)
        .containsExactly("2026-01", "2026-07");
    assertThat(member.contributionRates).extracting(rate -> rate.effectiveTo)
        .containsExactly("2026-06", null);
    assertThat(service.rateFor(member, "2026-06")).isEqualTo(100_000L);
    assertThat(service.rateFor(member, "2026-07")).isEqualTo(200_000L);
    verify(members).save(member);
  }

  @Test
  void changingAnnualPlanStartDoesNotChangeContributionRateEffectiveMonth() {
    MemberDocument member = memberWithInitialRate();
    when(members.findById("member-1")).thenReturn(Optional.of(member));

    MemberService service = new MemberService(members, audit);
    service.update("member-1", requestWithPlanStart(200_000, "2026-07", "2026-03"), "admin");

    assertThat(member.contributionStartMonth).isEqualTo("2026-03");
    assertThat(service.rateFor(member, "2026-02")).isEqualTo(100_000L);
    assertThat(service.rateFor(member, "2026-07")).isEqualTo(200_000L);
  }

  @Test
  void movingSameRateBoundaryReplacesThePreviousBoundary() {
    MemberDocument member = memberWithInitialRate();
    member.contributionRates = new ArrayList<>(List.of(
        new ContributionRate(100_000, "2026-01", "2026-07"),
        new ContributionRate(200_000, "2026-08", "2026-08"),
        new ContributionRate(200_000, "2026-09", null)));
    when(members.findById("member-1")).thenReturn(Optional.of(member));

    MemberService service = new MemberService(members, audit);
    service.update("member-1", request(200_000, "2026-09"), "admin");

    assertThat(member.contributionRates).extracting(rate -> rate.amount)
        .containsExactly(100_000L, 200_000L);
    assertThat(member.contributionRates).extracting(rate -> rate.effectiveFrom)
        .containsExactly("2026-01", "2026-09");
    assertThat(member.contributionRates).extracting(rate -> rate.effectiveTo)
        .containsExactly("2026-08", null);
    assertThat(service.rateFor(member, "2026-08")).isEqualTo(100_000L);
    assertThat(service.rateFor(member, "2026-09")).isEqualTo(200_000L);
  }

  private MemberDocument memberWithInitialRate() {
    MemberDocument member = new MemberDocument();
    member.id = "member-1";
    member.memberCode = "M001";
    member.fullName = "Test Member";
    member.email = "test@example.invalid";
    member.joinMonth = "2025-12";
    member.baselineMonth = "2026-01";
    member.contributionStartMonth = "2026-01";
    member.status = MemberStatus.ACTIVE;
    member.version = 1L;
    member.contributionRates = new ArrayList<>(List.of(new ContributionRate(100_000, "2026-01", null)));
    return member;
  }

  private ApiDtos.MemberUpsertRequest request(long amount, String effectiveFrom) {
    return requestWithPlanStart(amount, effectiveFrom, "2026-01");
  }

  private ApiDtos.MemberUpsertRequest requestWithPlanStart(long amount, String effectiveFrom, String planStart) {
    return new ApiDtos.MemberUpsertRequest("M001", "Test Member", "test@example.invalid", null,
        "2025-12", "2026-01", amount, MemberStatus.ACTIVE, planStart, 14, List.of(), effectiveFrom);
  }
}
