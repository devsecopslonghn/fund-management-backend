package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.ContributionPeriod;
import com.fundmanagement.api.domain.DuePeriodDocument;
import com.fundmanagement.api.domain.ManualPaymentDocument;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class PaymentAllocationYear {
  private PaymentAllocationYear() {}
  static boolean matches(ManualPaymentDocument payment, List<DuePeriodDocument> periods, int year) {
    if (payment.allocationYear != null) return payment.allocationYear == year;
    Set<String> ids = payment.allocations.stream().map(a -> a.duePeriodId).collect(Collectors.toSet());
    Set<Integer> years = periods.stream().filter(d -> ids.contains(d.id))
        .map(d -> ContributionPeriod.year(d.month)).collect(Collectors.toSet());
    if (years.size() == 1) return years.contains(year);
    if (!years.isEmpty()) return false;
    // An old annual reference may refer to a different year than its bank date.
    // With no allocations or stored boundary, keep the credit for explicit review.
    return false;
  }
}
