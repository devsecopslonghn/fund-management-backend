package com.fundmanagement.api.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Singleton distributed lease for the Gmail polling job. */
@Document("job_leases")
public class GmailPollingLeaseDocument {
  @Id public String id;
  public String owner;
  public String leaseToken;
  @Indexed public Instant expiresAt;
  public Instant acquiredAt;
  public Instant releasedAt;
}
