package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {
  @Mock BankTransactionRepository transactions; @Mock SacombankMessageParser parser; @Mock SettingsService settings; @Mock PaymentIntentRepository intents; @Mock PaymentService payments; @Mock AuditService audit; @Mock MongoTemplate mongo; @Mock MemberRepository members;
  @Test void rejectsWrongReceivingAccountBeforeLedgerInsert() {
    when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId("provider-wrong")).thenReturn(Optional.empty());
    when(parser.parse("synthetic wrong receiver")).thenReturn(new SacombankMessageParser.ParsedBankMessage(Instant.now(), 100_000, "IN", "0662222222", "QUY A001 082026", 900_000L));
    IngestionService service = service();
    assertThatThrownBy(() -> service.ingest(new ApiDtos.BankTransactionIngestionRequest("provider-wrong", "synthetic wrong receiver"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configured receiving");
    verify(transactions, never()).insert(any(BankTransactionDocument.class)); verify(transactions, never()).save(any(BankTransactionDocument.class));
  }
  @Test void recoversConfiguredAccountWhenParserSawAnotherNumericToken() {
    when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId("provider-recover")).thenReturn(Optional.empty());
    when(parser.parse("Account marker 0661111111")).thenReturn(new SacombankMessageParser.ParsedBankMessage(Instant.now(), 100_000, "IN", "091234567", "QUY A001 082026", 900_000L));
    BankTransactionDocument saved = new BankTransactionDocument(); saved.id = "tx-recover";
    when(transactions.insert(org.mockito.ArgumentMatchers.<BankTransactionDocument>any())).thenAnswer(invocation -> { BankTransactionDocument tx = invocation.getArgument(0); tx.id = saved.id; return tx; });
    IngestionService service = service();
    var result = service.ingest(new ApiDtos.BankTransactionIngestionRequest("provider-recover", "Account marker 0661111111"));
    assertThat(result.reconciliationStatus()).isEqualTo("NEEDS_REVIEW");
    verify(transactions).insert(org.mockito.ArgumentMatchers.<BankTransactionDocument>argThat(tx -> "0661111111".equals(tx.accountNumber)));
  }
  @Test void autoMatchesOnlyExactFreshIntentAndUsesProviderIdempotencyKey() {
    Instant created = Instant.parse("2026-08-24T07:30:00Z"); when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId("provider-auto")).thenReturn(Optional.empty());
    when(parser.parse("synthetic accepted")).thenReturn(new SacombankMessageParser.ParsedBankMessage(created.plusSeconds(30), 100_000, "IN", "0661111111", "REF EWX AB12CD34EF BANK", 900_000L));
    when(transactions.insert(any(BankTransactionDocument.class))).thenAnswer(invocation -> { BankTransactionDocument tx = invocation.getArgument(0); tx.id = "tx-auto"; return tx; }); BankTransactionDocument claimed = new BankTransactionDocument(); claimed.id = "tx-auto"; claimed.providerMessageId = "provider-auto"; claimed.occurredAt = created.plusSeconds(30); claimed.amount = 100_000; claimed.direction = "IN"; claimed.parsedContent = "REF EWX AB12CD34EF BANK"; claimed.reconciliationStatus = "AUTO_MATCHING"; when(mongo.findAndModify(any(), any(), any(), eq(BankTransactionDocument.class))).thenReturn(claimed);
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.id = "intent-auto"; intent.memberId = "member-a"; intent.totalAmount = 100_000; intent.transferContent = "AB12CD34EF"; intent.createdAt = created; intent.expiresAt = created.plusSeconds(1800); intent.status = IntentStatus.PENDING; intent.duePeriodIds = List.of("due-a"); when(intents.findByStatusAndTotalAmountAndExpiresAtAfter(eq(IntentStatus.PENDING), eq(100_000L), any())).thenReturn(List.of(intent)); when(payments.recordForIntent(any(), eq(100_000L), eq(created.plusSeconds(30)), eq("gmail:provider-auto"), eq("system:gmail-auto"))).thenReturn(new ApiDtos.ManualPaymentResponse("payment-auto", "member-a", 100_000, 0, List.of(new ApiDtos.AllocationResponse("due-a", 100_000))));
    service().ingest(new ApiDtos.BankTransactionIngestionRequest("provider-auto", "synthetic accepted"));
    verify(payments).recordForIntent(any(), eq(100_000L), eq(created.plusSeconds(30)), eq("gmail:provider-auto"), eq("system:gmail-auto")); verify(transactions).save(argThat(tx -> "MATCHED".equals(tx.reconciliationStatus) && tx.matchedMemberId.equals("member-a"))); verify(audit).record(eq("system:gmail-auto"), eq("BANK_TRANSACTION_AUTO_MATCHED"), eq("bank_transaction"), eq("tx-auto"), any(), any());
  }
  @Test void autoMatchesUniqueFullNameIgnoringCaseAndVietnameseAccents() {
    Instant occurred = Instant.parse("2026-08-24T07:30:30Z"); MemberDocument member = member("member-long", "LONGHN", "Hồ Ngọc Long");
    setupParsed("provider-name", occurred, "IBFT HO NGOC LONG chuyen tien"); when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(member)); when(intents.findByStatusAndTotalAmountAndExpiresAtAfter(eq(IntentStatus.PENDING), eq(100_000L), any())).thenReturn(List.of()); when(payments.recordAutomatic(eq(member.id), eq(100_000L), eq(occurred), eq("gmail:provider-name"), eq(2026), eq("system:gmail-auto"))).thenReturn(new ApiDtos.ManualPaymentResponse("payment-name", member.id, 100_000, 0, List.of(new ApiDtos.AllocationResponse("due-long", 100_000))));

    serviceWithMembers().ingest(new ApiDtos.BankTransactionIngestionRequest("provider-name", "synthetic accepted"));

    verify(payments).recordAutomatic(eq(member.id), eq(100_000L), eq(occurred), eq("gmail:provider-name"), eq(2026), eq("system:gmail-auto")); verify(transactions).save(argThat(tx -> "MATCHED".equals(tx.reconciliationStatus) && member.id.equals(tx.matchedMemberId)));
  }
  @Test void leavesAmbiguousFullNameForAdminReviewInsteadOfChoosingLongestName() {
    Instant occurred = Instant.parse("2026-08-24T07:30:30Z"); MemberDocument longName = member("member-long", "LONGHN", "Hồ Ngọc Long"); MemberDocument shortName = member("member-ngoc", "NGOCLONG", "Ngọc Long");
    setupParsed("provider-ambiguous", occurred, "IBFT HO NGOC LONG chuyen tien"); when(members.findByStatus(MemberStatus.ACTIVE)).thenReturn(List.of(longName, shortName)); when(intents.findByStatusAndTotalAmountAndExpiresAtAfter(eq(IntentStatus.PENDING), eq(100_000L), any())).thenReturn(List.of());

    serviceWithMembers().ingest(new ApiDtos.BankTransactionIngestionRequest("provider-ambiguous", "synthetic accepted"));

    verify(payments, never()).record(any(), any()); verify(payments, never()).recordAutomatic(any(), anyLong(), any(), any(), any(), any()); verify(payments, never()).recordForIntent(any(), anyLong(), any(), any(), any()); verify(transactions).save(argThat(tx -> "NEEDS_REVIEW".equals(tx.reconciliationStatus) && tx.matchCandidates.size() == 2));
  }
  @Test void responseExposesSafeMatchHintsAndKeepsLegacyProjectionShape() {
    BankTransactionDocument tx = new BankTransactionDocument(); tx.id = "tx-hints"; tx.providerMessageId = "provider-hints"; tx.matchMethod = "MEMBER_IDENTIFIER"; tx.conflictReason = "NO_MEMBER_IDENTIFIER";
    BankTransactionDocument.MatchCandidate candidate = new BankTransactionDocument.MatchCandidate(); candidate.memberId = "member-a"; candidate.confidence = 1.0; candidate.signals = List.of("FULL_NAME"); tx.matchCandidates = List.of(candidate);

    ApiDtos.BankTransactionResponse response = service().response(tx);

    assertThat(response.matchMethod()).isEqualTo("MEMBER_IDENTIFIER"); assertThat(response.conflictReason()).isEqualTo("NO_MEMBER_IDENTIFIER"); assertThat(response.matchCandidates()).extracting(ApiDtos.ReconciliationMatchCandidate::memberId).containsExactly("member-a"); assertThat(response.matchCandidates().getFirst().signals()).containsExactly("FULL_NAME");
  }
  private void setupParsed(String provider, Instant occurred, String content) {
    when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId(provider)).thenReturn(Optional.empty()); when(parser.parse("synthetic accepted")).thenReturn(new SacombankMessageParser.ParsedBankMessage(occurred, 100_000, "IN", "0661111111", content, 900_000L)); when(transactions.insert(any(BankTransactionDocument.class))).thenAnswer(invocation -> { BankTransactionDocument tx = invocation.getArgument(0); tx.id = provider; return tx; }); BankTransactionDocument claimed = new BankTransactionDocument(); claimed.id = provider; claimed.providerMessageId = provider; claimed.occurredAt = occurred; claimed.amount = 100_000; claimed.direction = "IN"; claimed.parsedContent = content; claimed.reconciliationStatus = "AUTO_MATCHING"; when(mongo.findAndModify(any(), any(), any(), eq(BankTransactionDocument.class))).thenReturn(claimed);
  }
  private MemberDocument member(String id, String code, String fullName) { MemberDocument member = new MemberDocument(); member.id = id; member.memberCode = code; member.fullName = fullName; member.status = MemberStatus.ACTIVE; return member; }
  private IngestionService serviceWithMembers() { return new IngestionService(transactions, parser, settings, intents, payments, audit, new GmailProperties(), mongo, null, members); }
  private IngestionService service() { return new IngestionService(transactions, parser, settings, intents, payments, audit, new GmailProperties(), mongo); }
  private ApiDtos.SettingsResponse settings(String account) { return new ApiDtos.SettingsResponse(100_000, .85, "Synthetic Bank", "970499", account, "SYNTHETIC FUND", false, false, true, true, "synthetic@example.invalid", false, "", 587, ""); }
}
