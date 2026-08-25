package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One persisted configuration document for the single v1 fund. Secrets are write-only. */
@Document("system_settings")
public class SystemSettingsDocument {
  @Id public String id;
  public long defaultContributionAmount;
  public double autoReconciliationThreshold;
  public String bankName;
  public String bankCode;
  public String accountNumber;
  public String accountHolder;
  public boolean dueSchedulerEnabled;
  public boolean startupCatchUpEnabled;
  public boolean ingestionEnabled;
  public boolean gmailEnabled;
  public String gmailSenderFilter;
  /** Full-mailbox polling interval controlled by Super Admin; stored in seconds. */
  public Integer gmailBatchPollIntervalSeconds;
  public boolean smtpEnabled;
  public String smtpHost;
  public int smtpPort;
  public String smtpFrom;
  /** Name of the external Secret/env reference; never the secret value. */
  public String smtpSecretRef;
  public Instant updatedAt;
  public String updatedBy;
}
