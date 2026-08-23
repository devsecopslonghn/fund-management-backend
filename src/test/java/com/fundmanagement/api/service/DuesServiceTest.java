package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DuesServiceTest {
  @Mock MemberRepository members;
  @Mock DuePeriodRepository dues;
  @Mock PaymentIntentRepository intents;
  @Mock MemberService memberService;

  @Test void generatesOneDueForEligibleActiveMemberAndSkipsExistingPeriod() {
    MemberDocument first = member("m-1", "2026-07"); MemberDocument second = member("m-2", "2026-09");
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(first, second));
    when(dues.findByMemberIdAndMonth("m-1", "2026-08")).thenReturn(Optional.empty());
    when(memberService.rateFor(first, "2026-08")).thenReturn(200_000L);
    FundProperties properties = new FundProperties(); DuesService service = new DuesService(members, dues, intents, memberService, properties);

    ApiDtos.GenerationResult result = service.generate("2026-08");

    ArgumentCaptor<com.fundmanagement.api.domain.DuePeriodDocument> captor = ArgumentCaptor.forClass(com.fundmanagement.api.domain.DuePeriodDocument.class);
    org.mockito.Mockito.verify(dues).save(captor.capture());
    assertThat(result.created()).isEqualTo(1); assertThat(result.skipped()).isEqualTo(1); assertThat(captor.getValue().memberId).isEqualTo("m-1"); assertThat(captor.getValue().amountDue).isEqualTo(200_000L);
  }
  private MemberDocument member(String id, String baseline) { MemberDocument member = new MemberDocument(); member.id = id; member.baselineMonth = baseline; member.status = MemberStatus.ACTIVE; return member; }
}
