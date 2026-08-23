package com.fundmanagement.api.domain;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("due_periods")
@CompoundIndex(name = "member_month_unique", def = "{'memberId': 1, 'month': 1}", unique = true)
public class DuePeriodDocument {
  @Id public String id;
  public String memberId;
  public String month;
  public long amountDue;
  public long amountPaid;
  public DueStatus status;
  public List<PaymentAllocation> payments = new ArrayList<>();
  public String notes;
  @Version public Long version;
}
