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
  private boolean dueSchedulerEnabled;
  private boolean startupCatchUpEnabled;
  private boolean ingestionEnabled;
  private boolean gmailEnabled;
  private String gmailSenderFilter = "info@sacombank.com.vn";
  private boolean smtpEnabled;
  private String smtpHost = "";
  private int smtpPort = 587;
  private String smtpFrom = "";
  private String smtpSecretRef = "";
  public String getBankName() { return bankName; } public void setBankName(String value) { bankName = value; }
  public String getBankCode() { return bankCode; } public void setBankCode(String value) { bankCode = value; }
  public String getAccountNumber() { return accountNumber; } public void setAccountNumber(String value) { accountNumber = value; }
  public String getAccountHolder() { return accountHolder; } public void setAccountHolder(String value) { accountHolder = value; }
  public String getQrImageBaseUrl() { return qrImageBaseUrl; } public void setQrImageBaseUrl(String value) { qrImageBaseUrl = value; }
  public int getPaymentIntentMinutes() { return paymentIntentMinutes; } public void setPaymentIntentMinutes(int value) { paymentIntentMinutes = value; }
  public long getDefaultContributionAmount() { return defaultContributionAmount; } public void setDefaultContributionAmount(long value) { defaultContributionAmount = value; }
  public double getAutoReconciliationThreshold() { return autoReconciliationThreshold; } public void setAutoReconciliationThreshold(double value) { autoReconciliationThreshold = value; }
  public boolean isDueSchedulerEnabled() { return dueSchedulerEnabled; } public void setDueSchedulerEnabled(boolean value) { dueSchedulerEnabled = value; }
  public boolean isStartupCatchUpEnabled() { return startupCatchUpEnabled; } public void setStartupCatchUpEnabled(boolean value) { startupCatchUpEnabled = value; }
  public boolean isIngestionEnabled() { return ingestionEnabled; } public void setIngestionEnabled(boolean value) { ingestionEnabled = value; }
  public boolean isGmailEnabled() { return gmailEnabled; } public void setGmailEnabled(boolean value) { gmailEnabled = value; }
  public String getGmailSenderFilter() { return gmailSenderFilter; } public void setGmailSenderFilter(String value) { gmailSenderFilter = value; }
  public boolean isSmtpEnabled() { return smtpEnabled; } public void setSmtpEnabled(boolean value) { smtpEnabled = value; }
  public String getSmtpHost() { return smtpHost; } public void setSmtpHost(String value) { smtpHost = value; }
  public int getSmtpPort() { return smtpPort; } public void setSmtpPort(int value) { smtpPort = value; }
  public String getSmtpFrom() { return smtpFrom; } public void setSmtpFrom(String value) { smtpFrom = value; }
  public String getSmtpSecretRef() { return smtpSecretRef; } public void setSmtpSecretRef(String value) { smtpSecretRef = value; }
}
