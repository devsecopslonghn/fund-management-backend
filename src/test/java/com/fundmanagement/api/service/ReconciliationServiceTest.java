package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {
  @Mock BankTransactionRepository transactions; @Mock IngestionService ingestion; @Mock PaymentService payments; @Mock AuditService audit; @Mock MemberService members; @Mock NotificationService notifications;
  @Test void ignoreReviewIsAuditedWithoutCreatingPayment() {
    BankTransactionDocument tx = new BankTransactionDocument(); tx.id = "tx-1"; tx.providerMessageId = "msg-1"; tx.reconciliationStatus = "NEEDS_REVIEW"; tx.occurredAt = Instant.now();
    when(transactions.findById("tx-1")).thenReturn(Optional.of(tx));
    when(ingestion.response(tx)).thenReturn(new ApiDtos.BankTransactionResponse("tx-1", "msg-1", tx.occurredAt, 100, "IN", null, null, "IGNORED", null, java.util.List.of(), tx.createdAt));
    var result = new ReconciliationService(transactions, ingestion, payments, audit, members, notifications).review("tx-1", new ApiDtos.ReconciliationReviewRequest(true, null, null), "admin");
    assertThat(result.reconciliationStatus()).isEqualTo("IGNORED"); verify(transactions).save(tx); verify(audit).record(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.eq("BANK_TRANSACTION_IGNORED"), org.mockito.ArgumentMatchers.eq("bank_transaction"), org.mockito.ArgumentMatchers.eq("tx-1"), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap()); org.mockito.Mockito.verifyNoInteractions(payments);
  }
}
