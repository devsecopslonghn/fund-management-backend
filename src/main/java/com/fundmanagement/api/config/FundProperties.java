package com.fundmanagement.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.fund")
public class FundProperties {
  private String bankName;
  private String bankCode;
  private String accountNumber;
  private String accountHolder;
  private String qrImageBaseUrl = "https://img.vietqr.io/image";
  private int paymentIntentMinutes = 30;
  private long defaultContributionAmount = 100000;
  private double autoReconciliationThreshold = 0.85;
  public String getBankName() { return bankName; } public void setBankName(String value) { bankName = value; }
  public String getBankCode() { return bankCode; } public void setBankCode(String value) { bankCode = value; }
  public String getAccountNumber() { return accountNumber; } public void setAccountNumber(String value) { accountNumber = value; }
  public String getAccountHolder() { return accountHolder; } public void setAccountHolder(String value) { accountHolder = value; }
  public String getQrImageBaseUrl() { return qrImageBaseUrl; } public void setQrImageBaseUrl(String value) { qrImageBaseUrl = value; }
  public int getPaymentIntentMinutes() { return paymentIntentMinutes; } public void setPaymentIntentMinutes(int value) { paymentIntentMinutes = value; }
  public long getDefaultContributionAmount() { return defaultContributionAmount; } public void setDefaultContributionAmount(long value) { defaultContributionAmount = value; }
  public double getAutoReconciliationThreshold() { return autoReconciliationThreshold; } public void setAutoReconciliationThreshold(double value) { autoReconciliationThreshold = value; }
}
