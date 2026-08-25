package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ExpenseDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.ExpenseRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExpenseService {
  private final ExpenseRepository expenses; private final AuditService audit;
  public ExpenseService(ExpenseRepository expenses, AuditService audit) { this.expenses = expenses; this.audit = audit; }
  public ApiDtos.ExpenseResponse create(ApiDtos.ExpenseRequest request, String actor) { ExpenseDocument expense = new ExpenseDocument(); apply(expense, request); expense.createdAt = Instant.now(); expense.updatedAt = expense.createdAt; expense.createdBy = actor; expenses.save(expense); audit.record(actor, "EXPENSE_CREATED", "expense", expense.id, Map.of(), snapshot(expense)); return toResponse(expense); }
  public ApiDtos.ExpenseResponse update(String id, ApiDtos.ExpenseRequest request, String actor) { ExpenseDocument expense = get(id); Map<String, Object> before = snapshot(expense); apply(expense, request); expense.updatedAt = Instant.now(); expenses.save(expense); audit.record(actor, "EXPENSE_UPDATED", "expense", id, before, snapshot(expense)); return toResponse(expense); }
  public void delete(String id, String actor) { ExpenseDocument expense = get(id); Map<String, Object> before = snapshot(expense); expense.deleted = true; expense.updatedAt = Instant.now(); expenses.save(expense); audit.record(actor, "EXPENSE_DELETED", "expense", id, before, Map.of("deleted", true)); }
  public List<ApiDtos.ExpenseResponse> list(LocalDate from, LocalDate to, String category) { LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from; LocalDate end = to == null ? LocalDate.now().plusYears(1) : to; if (end.isBefore(start)) throw new IllegalArgumentException("to must not precede from"); List<ExpenseDocument> result = category == null || category.isBlank() ? expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(start, end) : expenses.findByDeletedFalseAndDateBetweenAndCategoryOrderByDateDesc(start, end, category); return result.stream().map(this::toResponse).toList(); }
  public List<ApiDtos.PublicExpenseResponse> publicList(LocalDate from, LocalDate to, String category) { LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from; LocalDate end = to == null ? LocalDate.now().plusYears(1) : to; if (end.isBefore(start)) throw new IllegalArgumentException("to must not precede from"); List<ExpenseDocument> result = category == null || category.isBlank() ? expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(start, end) : expenses.findByDeletedFalseAndDateBetweenAndCategoryOrderByDateDesc(start, end, category); return result.stream().map(e -> new ApiDtos.PublicExpenseResponse(e.date, e.amount, e.purpose, e.category)).toList(); }
  public ExpenseDocument get(String id) { return expenses.findById(id).filter(e -> !e.deleted).orElseThrow(() -> new IllegalArgumentException("Expense not found")); }
  public ApiDtos.ExpenseResponse toResponse(ExpenseDocument e) { return new ApiDtos.ExpenseResponse(e.id, e.date, e.amount, e.purpose, e.category, e.requestedBy, e.notes, e.createdAt, e.updatedAt); }
  private void apply(ExpenseDocument e, ApiDtos.ExpenseRequest r) { e.date = r.date(); e.amount = r.amount(); e.purpose = r.purpose().trim(); e.category = blank(r.category()); e.requestedBy = blank(r.requestedBy()); e.notes = blank(r.notes()); }
  private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private Map<String, Object> snapshot(ExpenseDocument e) { return Map.of("date", e.date.toString(), "amount", e.amount, "purpose", e.purpose, "category", e.category == null ? "" : e.category, "deleted", e.deleted); }
}
