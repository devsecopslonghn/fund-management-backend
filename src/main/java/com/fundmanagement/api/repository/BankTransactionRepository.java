package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.BankTransactionDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BankTransactionRepository extends MongoRepository<BankTransactionDocument, String> {
  Optional<BankTransactionDocument> findByProviderMessageId(String providerMessageId);
  Page<BankTransactionDocument> findByReconciliationStatusOrderByOccurredAtDesc(String status, Pageable pageable);
  List<BankTransactionDocument> findByReconciliationStatus(String status);
}
