package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit_logs")
public class AuditLogDocument {
  @Id public String id;
  public String actor;
  public String action;
  public String entityType;
  public String entityId;
  public Map<String, Object> before;
  public Map<String, Object> after;
  @Indexed public Instant timestamp;
}
