package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.AuditLogDocument;
import com.fundmanagement.api.repository.AuditLogRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditLogRepository repository;
  public AuditService(AuditLogRepository repository) { this.repository = repository; }
  public void record(String actor, String action, String entityType, String entityId, Map<String, Object> before, Map<String, Object> after) {
    AuditLogDocument event = new AuditLogDocument(); event.actor = actor; event.action = action; event.entityType = entityType; event.entityId = entityId; event.before = before; event.after = after; event.timestamp = Instant.now(); repository.save(event);
  }
  public com.fundmanagement.api.dto.ApiDtos.AuditLogPageResponse list(int page, int size, String entityType) {
    if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page or size");
    var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("timestamp").descending());
    var result = entityType == null || entityType.isBlank() ? repository.findAllByOrderByTimestampDesc(pageable) : repository.findByEntityTypeOrderByTimestampDesc(entityType, pageable);
    return new com.fundmanagement.api.dto.ApiDtos.AuditLogPageResponse(result.getContent().stream().map(e -> new com.fundmanagement.api.dto.ApiDtos.AuditLogResponse(e.id, e.actor, e.action, e.entityType, e.entityId, e.before, e.after, e.timestamp)).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
  }
}
