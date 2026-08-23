package com.fundmanagement.api.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("expenses")
public class ExpenseDocument {
  @Id public String id;
  @Indexed public LocalDate date;
  public long amount;
  public String purpose;
  @Indexed public String category;
  public String requestedBy;
  public String notes;
  public boolean deleted;
  public String createdBy;
  public Instant createdAt;
  public Instant updatedAt;
  @Version public Long version;
}
