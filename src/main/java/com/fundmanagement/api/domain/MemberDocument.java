package com.fundmanagement.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
@CompoundIndex(name = "active_name_lookup", def = "{'status': 1, 'normalizedFullName': 1, 'fullName': 1}")
public class MemberDocument {
  @Id public String id;
  @Indexed(unique = true) public String memberCode;
  public String fullName;
  /** Lower-case, whitespace-collapsed lookup key used by the anonymous payment flow. */
  @Indexed public String normalizedFullName;
  public String email;
  public String phone;
  public String joinMonth;
  public String baselineMonth;
  /** Master contribution plan entered during import/member creation. */
  public String contributionStartMonth;
  public Integer annualContributionMonths;
  public List<String> initiallyPaidMonths = new ArrayList<>();
  @Indexed public MemberStatus status;
  public List<ContributionRate> contributionRates = new ArrayList<>();
  public Instant createdAt;
  public Instant updatedAt;
  @Version public Long version;
}
