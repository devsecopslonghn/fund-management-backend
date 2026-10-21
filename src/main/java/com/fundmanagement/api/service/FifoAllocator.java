package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ContributionPeriod;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.DueStatus;
import com.fundmanagement.api.domain.ManualPaymentDocument;
import com.fundmanagement.api.domain.PaymentAllocation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure allocation calculation shared by recording and repair previews. */
final class FifoAllocator {
  private FifoAllocator() {}

  static List<DuePeriodDocument> allocateRemaining(ManualPaymentDocument payment,
      List<DuePeriodDocument> periods, Integer year, Instant now, String method) {
    if (payment.allocations == null) payment.allocations = new ArrayList<>();
    long allocated = 0;
    for (var allocation : payment.allocations) allocated = Math.addExact(allocated, allocation.amount);
    long remaining = Math.subtractExact(payment.amount, allocated);
    if (remaining < 0) throw new IllegalStateException("Payment allocations exceed its amount");
    List<DuePeriodDocument> changed = new ArrayList<>();
    for (DuePeriodDocument due : periods.stream()
        .sorted(Comparator.comparing(d -> d.month, ContributionPeriod.comparator())).toList()) {
      if (remaining == 0) break;
      if (year != null && ContributionPeriod.year(due.month) != year) continue;
      long outstanding = Math.max(0, Math.subtractExact(due.amountDue, due.amountPaid));
      if (outstanding == 0) continue;
      long amount = Math.min(remaining, outstanding);
      due.amountPaid = Math.addExact(due.amountPaid, amount);
      due.status = due.amountPaid == due.amountDue ? DueStatus.PAID : DueStatus.PARTIAL;
      if (due.payments == null) due.payments = new ArrayList<>();
      due.payments.add(new PaymentAllocation(payment.id, amount, now, method));
      var existing = payment.allocations.stream().filter(a -> due.id.equals(a.duePeriodId)).findFirst();
      if (existing.isPresent()) existing.get().amount = Math.addExact(existing.get().amount, amount);
      else payment.allocations.add(new ManualPaymentDocument.ManualAllocation(due.id, amount));
      remaining -= amount;
      changed.add(due);
    }
    payment.unallocatedAmount = remaining;
    return changed;
  }
}
