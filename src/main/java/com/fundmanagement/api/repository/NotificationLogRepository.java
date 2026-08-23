package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.NotificationLogDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationLogRepository extends MongoRepository<NotificationLogDocument, String> {
  Optional<NotificationLogDocument> findByIdempotencyKey(String idempotencyKey);
}
