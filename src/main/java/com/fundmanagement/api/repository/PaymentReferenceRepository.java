package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.PaymentReferenceDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentReferenceRepository extends MongoRepository<PaymentReferenceDocument, String> {
  Optional<PaymentReferenceDocument> findByMemberIdAndMonth(String memberId, String month);
  Optional<PaymentReferenceDocument> findByMemberIdAndYear(String memberId, Integer year);
  Optional<PaymentReferenceDocument> findByCode(String code);
}
