package com.fundmanagement.api.repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.fundmanagement.api.domain.AuditLogDocument;
import java.time.Instant;
public interface AuditLogRepository extends MongoRepository<AuditLogDocument, String> {
  Page<AuditLogDocument> findAllByOrderByTimestampDesc(Pageable pageable);
  Page<AuditLogDocument> findByEntityTypeOrderByTimestampDesc(String entityType, Pageable pageable);
}
