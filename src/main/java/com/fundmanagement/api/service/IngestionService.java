package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.BankTransactionDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.BankTransactionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {
  private final BankTransactionRepository transactions; private final SacombankMessageParser parser; private final SettingsService settings;
  public IngestionService(BankTransactionRepository transactions, SacombankMessageParser parser, SettingsService settings) { this.transactions = transactions; this.parser = parser; this.settings = settings; }
  public ApiDtos.BankTransactionResponse ingest(ApiDtos.BankTransactionIngestionRequest request) {
    if (!settings.ingestionStatus().enabled()) throw new IllegalStateException("Bank ingestion is disabled");
    var existing = transactions.findByProviderMessageId(request.providerMessageId()); if (existing.isPresent()) return response(existing.get());
    BankTransactionDocument tx = new BankTransactionDocument(); tx.providerMessageId = request.providerMessageId().trim(); tx.rawPayload = request.rawPayload(); tx.createdAt = Instant.now(); tx.reconciliationStatus = "PARSE_FAILED";
    try { SacombankMessageParser.ParsedBankMessage parsed = parser.parse(request.rawPayload()); tx.occurredAt = parsed.occurredAt(); tx.amount = parsed.amount(); tx.direction = parsed.direction(); tx.accountNumber = parsed.accountNumber(); tx.parsedContent = parsed.parsedContent(); tx.availableBalance = parsed.availableBalance(); tx.reconciliationStatus = "NEEDS_REVIEW"; } catch (IllegalArgumentException ignored) { tx.occurredAt = tx.createdAt; }
    try { return response(transactions.insert(tx)); } catch (DuplicateKeyException duplicate) { return response(transactions.findByProviderMessageId(request.providerMessageId()).orElseThrow(() -> duplicate)); }
  }
  public ApiDtos.ReconciliationPageResponse reviewQueue(int page, int size) { var result = transactions.findByReconciliationStatusOrderByOccurredAtDesc("NEEDS_REVIEW", org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))); return new ApiDtos.ReconciliationPageResponse(result.getContent().stream().map(this::response).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }
  public ApiDtos.BankTransactionResponse response(BankTransactionDocument tx) { return new ApiDtos.BankTransactionResponse(tx.id, tx.providerMessageId, tx.occurredAt, tx.amount, tx.direction, tx.parsedContent, tx.availableBalance, tx.reconciliationStatus, tx.matchedMemberId, tx.matchedDuePeriodIds, tx.createdAt); }
}
