package com.fundmanagement.api.service;

import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.domain.SystemSettingsDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.SystemSettingsRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
  private static final String ID = "singleton";
  private final SystemSettingsRepository repository;
  private final FundProperties properties;
  private final AuditService audit;

  public SettingsService(SystemSettingsRepository repository, FundProperties properties, AuditService audit) {
    this.repository = repository; this.properties = properties; this.audit = audit;
  }

  public ApiDtos.SettingsResponse get() { return response(repository.findById(ID).orElseGet(this::defaults)); }

  public ApiDtos.SettingsResponse update(ApiDtos.SettingsRequest request, String actor) {
    SystemSettingsDocument current = repository.findById(ID).orElseGet(this::defaults);
    Map<String, Object> before = snapshot(current);
    current.defaultContributionAmount = request.defaultContributionAmount();
    current.autoReconciliationThreshold = request.autoReconciliationThreshold();
    current.bankName = request.bankName().trim(); current.bankCode = request.bankCode().trim();
    current.accountNumber = request.accountNumber().trim(); current.accountHolder = request.accountHolder().trim();
    current.dueSchedulerEnabled = request.dueSchedulerEnabled(); current.startupCatchUpEnabled = request.startupCatchUpEnabled();
    current.ingestionEnabled = request.ingestionEnabled(); current.gmailEnabled = request.gmailEnabled();
    current.gmailSenderFilter = blank(request.gmailSenderFilter()); current.smtpEnabled = request.smtpEnabled();
    current.smtpHost = blank(request.smtpHost()); current.smtpPort = request.smtpPort(); current.smtpFrom = blank(request.smtpFrom());
    current.smtpSecretRef = blank(request.smtpSecretRef());
    current.updatedAt = Instant.now(); current.updatedBy = actor; repository.save(current);
    audit.record(actor, "SETTINGS_UPDATED", "settings", ID, before, snapshot(current));
    return response(current);
  }

  public ApiDtos.IngestionStatusResponse ingestionStatus() {
    ApiDtos.SettingsResponse value = get();
    String mode = value.gmailEnabled() ? "gmail-oauth-read-only" : "disabled";
    return new ApiDtos.IngestionStatusResponse(value.ingestionEnabled(), value.gmailEnabled(), value.smtpEnabled(), value.gmailSenderFilter(), mode);
  }

  private SystemSettingsDocument defaults() {
    SystemSettingsDocument value = new SystemSettingsDocument(); value.id = ID;
    value.defaultContributionAmount = properties.getDefaultContributionAmount(); value.autoReconciliationThreshold = properties.getAutoReconciliationThreshold();
    value.bankName = properties.getBankName(); value.bankCode = properties.getBankCode(); value.accountNumber = properties.getAccountNumber(); value.accountHolder = properties.getAccountHolder();
    value.dueSchedulerEnabled = properties.isDueSchedulerEnabled(); value.startupCatchUpEnabled = properties.isStartupCatchUpEnabled(); value.ingestionEnabled = properties.isIngestionEnabled();
    value.gmailEnabled = properties.isGmailEnabled(); value.gmailSenderFilter = properties.getGmailSenderFilter(); value.smtpEnabled = properties.isSmtpEnabled(); value.smtpHost = properties.getSmtpHost(); value.smtpPort = properties.getSmtpPort(); value.smtpFrom = properties.getSmtpFrom(); value.smtpSecretRef = properties.getSmtpSecretRef();
    return value;
  }
  private ApiDtos.SettingsResponse response(SystemSettingsDocument value) { return new ApiDtos.SettingsResponse(value.defaultContributionAmount, value.autoReconciliationThreshold, value.bankName, value.bankCode, value.accountNumber, value.accountHolder, value.dueSchedulerEnabled, value.startupCatchUpEnabled, value.ingestionEnabled, value.gmailEnabled, value.gmailSenderFilter, value.smtpEnabled, value.smtpHost, value.smtpPort, value.smtpFrom); }
  private Map<String, Object> snapshot(SystemSettingsDocument value) { Map<String, Object> result = new java.util.LinkedHashMap<>(); result.put("defaultContributionAmount", value.defaultContributionAmount); result.put("autoReconciliationThreshold", value.autoReconciliationThreshold); result.put("bankName", value.bankName); result.put("bankCode", value.bankCode); result.put("dueSchedulerEnabled", value.dueSchedulerEnabled); result.put("startupCatchUpEnabled", value.startupCatchUpEnabled); result.put("ingestionEnabled", value.ingestionEnabled); result.put("gmailEnabled", value.gmailEnabled); result.put("smtpEnabled", value.smtpEnabled); result.put("smtpHost", value.smtpHost == null ? "" : value.smtpHost); result.put("smtpPort", value.smtpPort); result.put("smtpFrom", value.smtpFrom == null ? "" : value.smtpFrom); return result;
  }
  private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
