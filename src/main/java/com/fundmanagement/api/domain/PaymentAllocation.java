package com.fundmanagement.api.domain;

import java.time.Instant;
public class PaymentAllocation {
  public String paymentId;
  public long amount;
  public Instant matchedAt;
  public String method;
  public PaymentAllocation() {}
  public PaymentAllocation(String paymentId, long amount, Instant matchedAt, String method) { this.paymentId = paymentId; this.amount = amount; this.matchedAt = matchedAt; this.method = method; }
}
