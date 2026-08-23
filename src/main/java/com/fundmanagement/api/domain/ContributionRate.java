package com.fundmanagement.api.domain;

public class ContributionRate {
  public long amount;
  public String effectiveFrom;
  public String effectiveTo;
  public ContributionRate() {}
  public ContributionRate(long amount, String effectiveFrom, String effectiveTo) { this.amount = amount; this.effectiveFrom = effectiveFrom; this.effectiveTo = effectiveTo; }
}
