package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import com.fundmanagement.api.service.ExpenseService;
import com.fundmanagement.api.service.MemberService;
import com.fundmanagement.api.service.PaymentService;
import com.fundmanagement.api.service.SettingsService;
import com.fundmanagement.api.service.AuditService;
import com.fundmanagement.api.service.ReportService;
import com.fundmanagement.api.service.IngestionService;
import com.fundmanagement.api.service.ReconciliationService;
import com.fundmanagement.api.service.LegacyMigrationService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final MemberService members; private final DuesService dues; private final PaymentService payments; private final ExpenseService expenses; private final DashboardService dashboard; private final SettingsService settings; private final AuditService audit; private final ReportService reports; private final IngestionService ingestion; private final ReconciliationService reconciliation; private final LegacyMigrationService migration;
  public AdminController(MemberService members, DuesService dues, PaymentService payments, ExpenseService expenses, DashboardService dashboard, SettingsService settings, AuditService audit, ReportService reports, IngestionService ingestion, ReconciliationService reconciliation, LegacyMigrationService migration) { this.members = members; this.dues = dues; this.payments = payments; this.expenses = expenses; this.dashboard = dashboard; this.settings = settings; this.audit = audit; this.reports = reports; this.ingestion = ingestion; this.reconciliation = reconciliation; this.migration = migration; }
  @GetMapping("/members") public ApiDtos.MemberPageResponse listMembers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page or size"); return members.list(page, size); }
  @PostMapping("/members") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.CreatedMemberResponse createMember(@Valid @RequestBody ApiDtos.MemberUpsertRequest request, Principal principal) { return members.create(request, principal.getName()); }
  @PutMapping("/members/{id}") public ApiDtos.MemberResponse updateMember(@PathVariable String id, @Valid @RequestBody ApiDtos.MemberUpsertRequest request, Principal principal) { return members.update(id, request, principal.getName()); }
  @DeleteMapping("/members/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteMember(@PathVariable String id, Principal principal) { members.deactivate(id, principal.getName()); }
  @PostMapping("/dues/generate") public ApiDtos.GenerationResult generateDues(@RequestParam String month) { return dues.generate(month); }
  /** One-time/onboarding generation from each member's master contribution plan. */
  @PostMapping("/dues/generate-year") public ApiDtos.YearGenerationResult generateDuesYear(@RequestParam int year) { return dues.generateYear(year); }
  /** Master payment-plan grid: one explicit annual plan per active member. */
  @GetMapping("/payment-plans") public ApiDtos.PaymentPlanPageResponse listPaymentPlans(@RequestParam int year) { return dues.listPaymentPlans(year); }
  @PutMapping("/members/{id}/payment-plan") public ApiDtos.PaymentPlanResponse savePaymentPlan(@PathVariable String id, @Valid @RequestBody ApiDtos.PaymentPlanRequest request, Principal principal) { return dues.savePaymentPlan(id, request, principal.getName()); }
  @GetMapping("/dues") public ApiDtos.DuePeriodPageResponse listDues(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String month, @RequestParam(required = false) String memberId) { return dues.list(page, size, month, memberId); }
  @GetMapping("/dues/{id}") public ApiDtos.AdminDuePeriodResponse dueDetail(@PathVariable String id) { return dues.detail(id); }
  @PostMapping("/dues/{id}/adjustment") public ApiDtos.AdminDuePeriodResponse adjustDue(@PathVariable String id, @Valid @RequestBody ApiDtos.DueAdjustmentRequest request, Principal principal) { return dues.adjust(id, request, principal.getName()); }
  @PutMapping("/dues/{id}") public ApiDtos.AdminDuePeriodResponse updateDue(@PathVariable String id, @Valid @RequestBody ApiDtos.DueAdjustmentRequest request, Principal principal) { return dues.adjust(id, request, principal.getName()); }
  @PostMapping("/payments") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.ManualPaymentResponse recordPayment(@Valid @RequestBody ApiDtos.ManualPaymentRequest request, Principal principal) { return payments.record(request, principal.getName()); }
  @GetMapping("/expenses") public java.util.List<ApiDtos.ExpenseResponse> listExpenses(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String category) { return expenses.list(from, to, category); }
  @PostMapping("/expenses") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.ExpenseResponse createExpense(@Valid @RequestBody ApiDtos.ExpenseRequest request, Principal principal) { return expenses.create(request, principal.getName()); }
  @PutMapping("/expenses/{id}") public ApiDtos.ExpenseResponse updateExpense(@PathVariable String id, @Valid @RequestBody ApiDtos.ExpenseRequest request, Principal principal) { return expenses.update(id, request, principal.getName()); }
  @DeleteMapping("/expenses/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteExpense(@PathVariable String id, Principal principal) { expenses.delete(id, principal.getName()); }
  @GetMapping("/dashboard") public ApiDtos.DashboardResponse dashboard() { return dashboard.adminOverview(); }
  @GetMapping("/statistics") public ApiDtos.DashboardResponse statistics() { return dashboard.adminOverview(); }
  @GetMapping("/reports/expenses") public ApiDtos.ExpenseReportResponse expenseReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return reports.expenseReport(from, to); }
  @GetMapping("/reports/expenses/export") public ResponseEntity<byte[]> exportExpenses(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=expenses.csv").contentType(MediaType.parseMediaType("text/csv")).body(reports.expenseCsv(from, to)); }
  @GetMapping("/reports/export") public ResponseEntity<byte[]> exportSummary(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) { return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fund-summary.csv").contentType(MediaType.parseMediaType("text/csv")).body(reports.summaryCsv(from, to)); }
  @GetMapping("/settings") public ApiDtos.SettingsResponse settings() { return settings.get(); }
  @PutMapping("/settings") public ApiDtos.SettingsResponse updateSettings(@Valid @RequestBody ApiDtos.SettingsRequest request, Principal principal) { return settings.update(request, principal.getName()); }
  @GetMapping("/ingestion/status") public ApiDtos.IngestionStatusResponse ingestionStatus() { return settings.ingestionStatus(); }
  @PostMapping("/reconciliation/ingest") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.BankTransactionResponse ingest(@Valid @RequestBody ApiDtos.BankTransactionIngestionRequest request) { return ingestion.ingest(request); }
  @GetMapping("/reconciliation/review") public ApiDtos.ReconciliationPageResponse reconciliationReviewQueue(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return ingestion.reviewQueue(page, size); }
  @GetMapping("/reconciliation/matched") public ApiDtos.ReconciliationPageResponse reconciliationMatched(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return ingestion.matchedQueue(page, size); }
  @PostMapping("/reconciliation/{id}/review") public ApiDtos.BankTransactionResponse reviewReconciliation(@PathVariable String id, @Valid @RequestBody ApiDtos.ReconciliationReviewRequest request, Principal principal) { return reconciliation.review(id, request, principal.getName()); }
  @PostMapping("/reconciliation/{id}/reverse") public ApiDtos.BankTransactionResponse reverseReconciliation(@PathVariable String id, @Valid @RequestBody ApiDtos.PaymentReversalRequest request, Principal principal) { return reconciliation.reverse(id, request, principal.getName()); }
  @GetMapping("/audit-logs") public ApiDtos.AuditLogPageResponse auditLogs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String entityType) { return audit.list(page, size, entityType); }
  @GetMapping("/migrations/legacy/preview") public ApiDtos.LegacyMigrationPreviewResponse legacyPreview() { return migration.preview(); }
  @PostMapping("/migrations/legacy/apply") public ApiDtos.LegacyMigrationPreviewResponse legacyApply(@RequestParam(defaultValue = "true") boolean dryRun) { return migration.apply(dryRun); }
}
