package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
public class MemberDocument {
  @Id public String id;
  @Indexed(unique = true) public String memberCode;
  public String fullName;
  public String email;
  public String phone;
  public String joinMonth;
  public String baselineMonth;
  public MemberStatus status;
  @Indexed(unique = true, sparse = true) public String magicTokenHash;
  public List<ContributionRate> contributionRates = new ArrayList<>();
  public Instant createdAt;
  public Instant updatedAt;
  @Version public Long version;
}
