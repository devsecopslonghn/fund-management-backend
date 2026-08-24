package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Opaque random transfer code. New records are stable for one member/year; month is retained for legacy data. */
@Document("payment_references")
@CompoundIndex(name = "member_month_unique", def = "{'memberId': 1, 'month': 1}", unique = true)
@CompoundIndex(name = "member_year_unique", def = "{'memberId': 1, 'year': 1}", unique = true, sparse = true)
public class PaymentReferenceDocument {
  @Id public String id;
  public String memberId;
  /** Four digit payment-plan year for new references. */
  public Integer year;
  /** Legacy month scope; do not remove because historical QR contents use it. */
  public String month;
  @Indexed(unique = true) public String code;
  public Instant createdAt;
}
