package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("gmail_credentials")
public class GmailCredentialDocument {
  @Id public String id;
  /** AES-GCM ciphertext; no plaintext access or refresh token is persisted. */
  public String encryptedRefreshToken;
  public String mailbox;
  public List<String> scopes = new ArrayList<>();
  public Instant connectedAt;
  public String connectedBy;
}
