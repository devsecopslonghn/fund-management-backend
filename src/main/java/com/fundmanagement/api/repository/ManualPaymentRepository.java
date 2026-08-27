package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.ManualPaymentDocument;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ManualPaymentRepository extends MongoRepository<ManualPaymentDocument, String> { Optional<ManualPaymentDocument> findByIdempotencyKey(String idempotencyKey); java.util.List<ManualPaymentDocument> findByMemberIdOrderByOccurredAtAscCreatedAtAsc(String memberId); long countByOccurredAtBetween(Instant from, Instant to); java.util.List<ManualPaymentDocument> findByOccurredAtBetween(Instant from, Instant to); }
