package com.fundmanagement.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime-only Gmail OAuth configuration. Values must be injected from an external Secret. */
@ConfigurationProperties(prefix = "app.gmail")
public class GmailProperties {
  private boolean enabled;
  private boolean pollingEnabled;
  private boolean eventEnabled;
  private String clientId = "";
  private String clientSecret = "";
  private String redirectUri = "";
  private String tokenEncryptionKey = "";
  private String monitoredMailbox = "me";
  private String senderFilter = "info@sacombank.com.vn";
  private int stateTtlSeconds = 600;
  private int leaseDurationSeconds = 900;
  private int intentMatchSeconds = 300;
  private long batchPollIntervalMs = 60000;
  private String pubsubTopicName = "";
  private String pubsubSharedToken = "";
  private int watchRenewalHours = 24;
  private String authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
  private String tokenEndpoint = "https://oauth2.googleapis.com/token";
  private String apiBaseUrl = "https://gmail.googleapis.com/gmail/v1";
  public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
  public boolean isPollingEnabled() { return pollingEnabled; } public void setPollingEnabled(boolean value) { pollingEnabled = value; }
  public boolean isEventEnabled() { return eventEnabled; } public void setEventEnabled(boolean value) { eventEnabled = value; }
  public String getClientId() { return clientId; } public void setClientId(String value) { clientId = value; }
  public String getClientSecret() { return clientSecret; } public void setClientSecret(String value) { clientSecret = value; }
  public String getRedirectUri() { return redirectUri; } public void setRedirectUri(String value) { redirectUri = value; }
  public String getTokenEncryptionKey() { return tokenEncryptionKey; } public void setTokenEncryptionKey(String value) { tokenEncryptionKey = value; }
  public String getMonitoredMailbox() { return monitoredMailbox; } public void setMonitoredMailbox(String value) { monitoredMailbox = value; }
  public String getSenderFilter() { return senderFilter; } public void setSenderFilter(String value) { senderFilter = value; }
  public int getStateTtlSeconds() { return stateTtlSeconds; } public void setStateTtlSeconds(int value) { stateTtlSeconds = value; }
  public int getLeaseDurationSeconds() { return leaseDurationSeconds; } public void setLeaseDurationSeconds(int value) { leaseDurationSeconds = value; }
  public int getIntentMatchSeconds() { return intentMatchSeconds; } public void setIntentMatchSeconds(int value) { intentMatchSeconds = value; }
  public long getBatchPollIntervalMs() { return batchPollIntervalMs; } public void setBatchPollIntervalMs(long value) { batchPollIntervalMs = value; }
  public String getPubsubTopicName() { return pubsubTopicName; } public void setPubsubTopicName(String value) { pubsubTopicName = value; }
  public String getPubsubSharedToken() { return pubsubSharedToken; } public void setPubsubSharedToken(String value) { pubsubSharedToken = value; }
  public int getWatchRenewalHours() { return watchRenewalHours; } public void setWatchRenewalHours(int value) { watchRenewalHours = value; }
  public String getAuthorizeEndpoint() { return authorizeEndpoint; } public void setAuthorizeEndpoint(String value) { authorizeEndpoint = value; }
  public String getTokenEndpoint() { return tokenEndpoint; } public void setTokenEndpoint(String value) { tokenEndpoint = value; }
  public String getApiBaseUrl() { return apiBaseUrl; } public void setApiBaseUrl(String value) { apiBaseUrl = value; }
}
