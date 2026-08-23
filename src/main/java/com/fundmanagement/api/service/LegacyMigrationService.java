package com.fundmanagement.api.service;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import com.fundmanagement.api.repository.MemberRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only inventory/preview boundary for the legacy workbook migration. */
@Service
public class LegacyMigrationService {
  private final MemberRepository members; private final DuePeriodRepository dues; private final ManualPaymentRepository payments; private final boolean applyEnabled;
  public LegacyMigrationService(MemberRepository members, DuePeriodRepository dues, ManualPaymentRepository payments, @Value("${app.migration.apply-enabled:false}") boolean applyEnabled) { this.members = members; this.dues = dues; this.payments = payments; this.applyEnabled = applyEnabled; }
  public ApiDtos.LegacyMigrationPreviewResponse preview() { return new ApiDtos.LegacyMigrationPreviewResponse(true, applyEnabled, members.count(), dues.count(), payments.count(), "Preview only; no legacy records were mutated"); }
  public ApiDtos.LegacyMigrationPreviewResponse apply(boolean dryRun) { if (dryRun || !applyEnabled) return new ApiDtos.LegacyMigrationPreviewResponse(true, applyEnabled, members.count(), dues.count(), payments.count(), "Apply is disabled or dryRun=true; no records were mutated"); throw new IllegalStateException("Legacy migration requires a reviewed, versioned import implementation"); }
}
