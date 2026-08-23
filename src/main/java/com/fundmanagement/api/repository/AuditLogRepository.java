package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.AuditLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface AuditLogRepository extends MongoRepository<AuditLogDocument, String> {}
