package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.AuditLogDocument;
import com.fundmanagement.api.repository.AuditLogRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditLogRepository repository;
  public AuditService(AuditLogRepository repository) { this.repository = repository; }
  public void record(String actor, String action, String entityType, String entityId, Map<String, Object> before, Map<String, Object> after) {
    AuditLogDocument event = new AuditLogDocument(); event.actor = actor; event.action = action; event.entityType = entityType; event.entityId = entityId; event.before = before; event.after = after; event.timestamp = Instant.now(); repository.save(event);
  }
}
