package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.DuePeriodDocument;
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
  @Mock SettingsService settings;

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
  @Test void anonymousLookupRequiresMemberCodeWhenNamesCollide() {
    MemberDocument first = member("m-1", "2026-07"); first.fullName = "Nguyễn Văn A"; first.memberCode = "A001";
    MemberDocument second = member("m-2", "2026-07"); second.fullName = "  NGUYỄN   VĂN A "; second.memberCode = "A002";
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(first, second));
    when(memberService.normalizeName(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().replaceAll("\\s+", " ").toLowerCase());
    DuesService service = new DuesService(members, dues, intents, memberService, new FundProperties());

    ApiDtos.PublicPaymentOptionsResponse result = service.publicPaymentOptions(new ApiDtos.PublicPaymentOptionsRequest("Nguyễn Văn A", null));

    assertThat(result.requiresMemberCode()).isTrue();
    assertThat(result.matches()).extracting(ApiDtos.PublicMemberMatch::memberCode).containsExactly("A001", "A002");
    assertThat(result.duePeriods()).isEmpty();
  }

  @Test void anonymousLookupReturnsOnlyOutstandingDuePeriods() {
    MemberDocument member = member("m-1", "2026-07"); member.fullName = "Nguyễn Văn A"; member.memberCode = "A001";
    DuePeriodDocument unpaid = due("due-1", "2026-08", 100_000, 20_000);
    DuePeriodDocument paid = due("due-2", "2026-07", 100_000, 100_000);
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(member));
    when(memberService.normalizeName(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().replaceAll("\\s+", " ").toLowerCase());
    when(dues.findByMemberIdOrderByMonthAsc("m-1")).thenReturn(List.of(paid, unpaid));
    DuesService service = new DuesService(members, dues, intents, memberService, new FundProperties());

    ApiDtos.PublicPaymentOptionsResponse result = service.publicPaymentOptions(new ApiDtos.PublicPaymentOptionsRequest("Nguyễn Văn A", null));

    assertThat(result.requiresMemberCode()).isFalse();
    assertThat(result.duePeriods()).extracting(ApiDtos.PublicDuePeriodResponse::id).containsExactly("due-1");
    assertThat(result.duePeriods().getFirst().outstandingAmount()).isEqualTo(80_000);
  }

  @Test void anonymousIntentUsesServerCalculatedAmountAndRejectsPaidPeriods() {
    MemberDocument member = member("m-1", "2026-07"); member.fullName = "Nguyễn Văn A"; member.memberCode = "A001";
    DuePeriodDocument unpaid = due("due-1", "2026-08", 100_000, 20_000);
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(member));
    when(memberService.normalizeName(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().replaceAll("\\s+", " ").toLowerCase());
    when(dues.findByMemberIdAndIdIn("m-1", java.util.Set.of("due-1"))).thenReturn(List.of(unpaid));
    FundProperties properties = new FundProperties(); properties.setBankCode("970422"); properties.setAccountNumber("123456789"); properties.setAccountHolder("TEST FUND");
    when(intents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DuesService service = new DuesService(members, dues, intents, memberService, properties);

    ApiDtos.PublicPaymentIntentResponse result = service.createPublicIntent(new ApiDtos.PublicPaymentIntentRequest("Nguyễn Văn A", null, List.of("due-1")));

    assertThat(result.amount()).isEqualTo(80_000);
    assertThat(result.transferContent()).isEqualTo("QUY A001 082026");
    assertThatThrownBy(() -> service.createPublicIntent(new ApiDtos.PublicPaymentIntentRequest("Nguyễn Văn A", null, List.of("due-2"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
  }

  @Test void publicQrUsesPersistedBankSettingsWithoutRestart() {
    MemberDocument member = member("m-1", "2026-07"); member.fullName = "Nguyễn Văn A"; member.memberCode = "A001";
    DuePeriodDocument unpaid = due("due-1", "2026-08", 100_000, 0);
    when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(member));
    when(memberService.normalizeName(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().replaceAll("\\s+", " ").toLowerCase());
    when(dues.findByMemberIdAndIdIn("m-1", java.util.Set.of("due-1"))).thenReturn(List.of(unpaid));
    when(intents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(settings.get()).thenReturn(new ApiDtos.SettingsResponse(100_000, .85, "Sacombank Updated", "970499", "99887766", "UPDATED FUND HOLDER", false, false, false, false, "", false, "", 587, ""));
    FundProperties properties = new FundProperties(); properties.setBankCode("970422"); properties.setAccountNumber("old-account"); properties.setAccountHolder("OLD HOLDER");
    DuesService service = new DuesService(members, dues, intents, memberService, properties, settings);

    ApiDtos.PublicPaymentIntentResponse result = service.createPublicIntent(new ApiDtos.PublicPaymentIntentRequest("Nguyễn Văn A", null, List.of("due-1")));

    assertThat(result.bankName()).isEqualTo("Sacombank Updated"); assertThat(result.accountNumber()).isEqualTo("99887766"); assertThat(result.accountHolder()).isEqualTo("UPDATED FUND HOLDER"); assertThat(result.qrImageUrl()).contains("970499-99887766");
  }

  private DuePeriodDocument due(String id, String month, long due, long paid) { DuePeriodDocument value = new DuePeriodDocument(); value.id = id; value.month = month; value.amountDue = due; value.amountPaid = paid; value.status = paid >= due ? com.fundmanagement.api.domain.DueStatus.PAID : com.fundmanagement.api.domain.DueStatus.PARTIAL; return value; }
  private MemberDocument member(String id, String baseline) { MemberDocument member = new MemberDocument(); member.id = id; member.baselineMonth = baseline; member.status = MemberStatus.ACTIVE; return member; }
}
