package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.service.DashboardService;
import com.fundmanagement.api.service.DuesService;
import com.fundmanagement.api.service.ExpenseService;
import com.fundmanagement.api.service.MemberService;
import com.fundmanagement.api.service.PaymentService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
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
  private final MemberService members; private final DuesService dues; private final PaymentService payments; private final ExpenseService expenses; private final DashboardService dashboard;
  public AdminController(MemberService members, DuesService dues, PaymentService payments, ExpenseService expenses, DashboardService dashboard) { this.members = members; this.dues = dues; this.payments = payments; this.expenses = expenses; this.dashboard = dashboard; }
  @GetMapping("/members") public ApiDtos.MemberPageResponse listMembers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page or size"); return members.list(page, size); }
  @PostMapping("/members") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.CreatedMemberResponse createMember(@Valid @RequestBody ApiDtos.MemberUpsertRequest request, Principal principal) { return members.create(request, principal.getName()); }
  @PutMapping("/members/{id}") public ApiDtos.MemberResponse updateMember(@PathVariable String id, @Valid @RequestBody ApiDtos.MemberUpsertRequest request, Principal principal) { return members.update(id, request, principal.getName()); }
  @DeleteMapping("/members/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteMember(@PathVariable String id, Principal principal) { members.deactivate(id, principal.getName()); }
  @PostMapping("/dues/generate") public ApiDtos.GenerationResult generateDues(@RequestParam String month) { return dues.generate(month); }
  @PostMapping("/payments") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.ManualPaymentResponse recordPayment(@Valid @RequestBody ApiDtos.ManualPaymentRequest request, Principal principal) { return payments.record(request, principal.getName()); }
  @GetMapping("/expenses") public java.util.List<ApiDtos.ExpenseResponse> listExpenses(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String category) { return expenses.list(from, to, category); }
  @PostMapping("/expenses") @ResponseStatus(HttpStatus.CREATED) public ApiDtos.ExpenseResponse createExpense(@Valid @RequestBody ApiDtos.ExpenseRequest request, Principal principal) { return expenses.create(request, principal.getName()); }
  @PutMapping("/expenses/{id}") public ApiDtos.ExpenseResponse updateExpense(@PathVariable String id, @Valid @RequestBody ApiDtos.ExpenseRequest request, Principal principal) { return expenses.update(id, request, principal.getName()); }
  @DeleteMapping("/expenses/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteExpense(@PathVariable String id, Principal principal) { expenses.delete(id, principal.getName()); }
  @GetMapping("/dashboard") public ApiDtos.DashboardResponse dashboard() { return dashboard.adminOverview(); }
  @GetMapping("/statistics") public ApiDtos.DashboardResponse statistics() { return dashboard.adminOverview(); }
  @GetMapping("/settings") public void settings() { throw new UnsupportedOperationException(); }
  @PutMapping("/settings") public void updateSettings() { throw new UnsupportedOperationException(); }
  @GetMapping("/reconciliation/review") public void reconciliationReviewQueue() { throw new UnsupportedOperationException(); }
}
