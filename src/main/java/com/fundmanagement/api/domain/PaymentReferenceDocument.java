package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Stable random transfer code for one member and due month. */
@Document("payment_references")
@CompoundIndex(name = "member_month_unique", def = "{'memberId': 1, 'month': 1}", unique = true)
public class PaymentReferenceDocument {
  @Id public String id;
  public String memberId;
  public String month;
  @Indexed(unique = true) public String code;
  public Instant createdAt;
}
