package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ExpenseDocument;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.ExpenseRepository;
import com.fundmanagement.api.repository.ManualPaymentRepository;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final ExpenseRepository expenses; private final ManualPaymentRepository payments;
  public ReportService(ExpenseRepository expenses, ManualPaymentRepository payments) { this.expenses = expenses; this.payments = payments; }

  public ApiDtos.ExpenseReportResponse expenseReport(LocalDate from, LocalDate to) {
    LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from; LocalDate end = to == null ? LocalDate.now() : to;
    if (end.isBefore(start)) throw new IllegalArgumentException("to must not precede from");
    List<ExpenseDocument> rows = expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(start, end);
    Map<String, Long> byCategory = new LinkedHashMap<>();
    long total = 0; for (ExpenseDocument row : rows) { total = Math.addExact(total, row.amount); String key = row.category == null || row.category.isBlank() ? "uncategorized" : row.category; byCategory.merge(key, row.amount, Math::addExact); }
    Map<YearMonth, long[]> monthly = new java.util.TreeMap<>(); for (ExpenseDocument row : rows) { monthly.computeIfAbsent(YearMonth.from(row.date), ignored -> new long[2])[1] = Math.addExact(monthly.get(YearMonth.from(row.date))[1], row.amount); }
    return new ApiDtos.ExpenseReportResponse(total, byCategory, monthly.entrySet().stream().map(e -> new ApiDtos.MonthlySeriesResponse(e.getKey().toString(), 0, e.getValue()[1])).toList());
  }

  public byte[] expenseCsv(LocalDate from, LocalDate to) {
    LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from; LocalDate end = to == null ? LocalDate.now() : to;
    if (end.isBefore(start)) throw new IllegalArgumentException("to must not precede from");
    StringBuilder csv = new StringBuilder("id,date,amount,purpose,category,requestedBy,notes\n");
    for (ExpenseDocument e : expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(start, end)) csv.append(csv(e.id)).append(',').append(csv(String.valueOf(e.date))).append(',').append(e.amount).append(',').append(csv(e.purpose)).append(',').append(csv(e.category)).append(',').append(csv(e.requestedBy)).append(',').append(csv(e.notes)).append('\n');
    return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
  public byte[] summaryCsv(LocalDate from, LocalDate to) {
    LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from; LocalDate end = to == null ? LocalDate.now() : to;
    if (end.isBefore(start)) throw new IllegalArgumentException("to must not precede from");
    Map<YearMonth, long[]> values = new java.util.TreeMap<>();
    for (var payment : payments.findByOccurredAtBetween(start.atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))) values.computeIfAbsent(YearMonth.from(payment.occurredAt.atZone(ZoneOffset.UTC)), ignored -> new long[2])[0] = Math.addExact(values.get(YearMonth.from(payment.occurredAt.atZone(ZoneOffset.UTC)))[0], payment.amount);
    for (ExpenseDocument expense : expenses.findByDeletedFalseAndDateBetweenOrderByDateDesc(start, end)) values.computeIfAbsent(YearMonth.from(expense.date), ignored -> new long[2])[1] = Math.addExact(values.get(YearMonth.from(expense.date))[1], expense.amount);
    StringBuilder csv = new StringBuilder("month,income,expense,balanceDelta\n"); for (var entry : values.entrySet()) csv.append(entry.getKey()).append(',').append(entry.getValue()[0]).append(',').append(entry.getValue()[1]).append(',').append(Math.subtractExact(entry.getValue()[0], entry.getValue()[1])).append('\n'); return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
  private String csv(String value) { if (value == null) return ""; return "\"" + value.replace("\"", "\"\"") + "\""; }
}
