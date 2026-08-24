package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fundmanagement.api.config.GmailProperties;
import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
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
  @Mock BankTransactionRepository transactions; @Mock SacombankMessageParser parser; @Mock SettingsService settings; @Mock PaymentIntentRepository intents; @Mock PaymentService payments; @Mock AuditService audit; @Mock MongoTemplate mongo;
  @Test void rejectsWrongReceivingAccountBeforeLedgerInsert() {
    when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId("provider-wrong")).thenReturn(Optional.empty());
    when(parser.parse("synthetic wrong receiver")).thenReturn(new SacombankMessageParser.ParsedBankMessage(Instant.now(), 100_000, "IN", "0662222222", "QUY A001 082026", 900_000L));
    IngestionService service = service();
    assertThatThrownBy(() -> service.ingest(new ApiDtos.BankTransactionIngestionRequest("provider-wrong", "synthetic wrong receiver"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configured receiving");
    verify(transactions, never()).insert(any(BankTransactionDocument.class)); verify(transactions, never()).save(any(BankTransactionDocument.class));
  }
  @Test void autoMatchesOnlyExactFreshIntentAndUsesProviderIdempotencyKey() {
    Instant created = Instant.parse("2026-08-24T07:30:00Z"); when(settings.ingestionStatus()).thenReturn(new ApiDtos.IngestionStatusResponse(true, true, false, "synthetic", "test")); when(settings.get()).thenReturn(settings("0661111111")); when(transactions.findByProviderMessageId("provider-auto")).thenReturn(Optional.empty());
    when(parser.parse("synthetic accepted")).thenReturn(new SacombankMessageParser.ParsedBankMessage(created.plusSeconds(30), 100_000, "IN", "0661111111", "REF EWX AB12CD34EF BANK", 900_000L));
    when(transactions.insert(any(BankTransactionDocument.class))).thenAnswer(invocation -> { BankTransactionDocument tx = invocation.getArgument(0); tx.id = "tx-auto"; return tx; }); BankTransactionDocument claimed = new BankTransactionDocument(); claimed.id = "tx-auto"; claimed.providerMessageId = "provider-auto"; claimed.occurredAt = created.plusSeconds(30); claimed.amount = 100_000; claimed.direction = "IN"; claimed.parsedContent = "REF EWX AB12CD34EF BANK"; claimed.reconciliationStatus = "AUTO_MATCHING"; when(mongo.findAndModify(any(), any(), any(), eq(BankTransactionDocument.class))).thenReturn(claimed);
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.id = "intent-auto"; intent.memberId = "member-a"; intent.totalAmount = 100_000; intent.transferContent = "AB12CD34EF"; intent.createdAt = created; intent.expiresAt = created.plusSeconds(1800); intent.status = IntentStatus.PENDING; when(intents.findByStatusAndTotalAmountAndExpiresAtAfter(eq(IntentStatus.PENDING), eq(100_000L), any())).thenReturn(List.of(intent)); when(payments.record(any(), eq("system:gmail-auto"))).thenReturn(new ApiDtos.ManualPaymentResponse("payment-auto", "member-a", 100_000, 0, List.of(new ApiDtos.AllocationResponse("due-a", 100_000))));
    service().ingest(new ApiDtos.BankTransactionIngestionRequest("provider-auto", "synthetic accepted"));
    verify(payments).record(argThat(request -> request.memberId().equals("member-a") && request.amount() == 100_000 && request.idempotencyKey().equals("gmail:provider-auto")), eq("system:gmail-auto")); verify(transactions).save(argThat(tx -> "MATCHED".equals(tx.reconciliationStatus) && tx.matchedMemberId.equals("member-a"))); verify(audit).record(eq("system:gmail-auto"), eq("BANK_TRANSACTION_AUTO_MATCHED"), eq("bank_transaction"), eq("tx-auto"), any(), any());
  }
  private IngestionService service() { return new IngestionService(transactions, parser, settings, intents, payments, audit, new GmailProperties(), mongo); }
  private ApiDtos.SettingsResponse settings(String account) { return new ApiDtos.SettingsResponse(100_000, .85, "Synthetic Bank", "970499", account, "SYNTHETIC FUND", false, false, true, true, "synthetic@example.invalid", false, "", 587, ""); }
}
