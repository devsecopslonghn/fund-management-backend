package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.PaymentIntentDocument;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface PaymentIntentRepository extends MongoRepository<PaymentIntentDocument, String> { Optional<PaymentIntentDocument> findByIdAndMemberId(String id, String memberId); java.util.List<PaymentIntentDocument> findByMemberId(String memberId); java.util.List<PaymentIntentDocument> findByStatus(com.fundmanagement.api.domain.IntentStatus status); java.util.List<PaymentIntentDocument> findByStatusAndTotalAmountAndExpiresAtAfter(com.fundmanagement.api.domain.IntentStatus status, long totalAmount, Instant expiresAt); }
