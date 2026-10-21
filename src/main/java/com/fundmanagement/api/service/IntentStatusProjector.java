package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.IntentStatus;
import com.fundmanagement.api.repository.DuePeriodRepository;
import com.fundmanagement.api.repository.PaymentIntentRepository;

final class IntentStatusProjector {
  private IntentStatusProjector() {}
  static void refresh(String memberId, DuePeriodRepository dues, PaymentIntentRepository intents) {
    for (var intent : intents.findByMemberId(memberId)) {
      if (intent.status == IntentStatus.EXPIRED || intent.status == IntentStatus.CREDIT) continue;
      var selected = dues.findAllById(intent.duePeriodIds);
      if (selected.isEmpty() || selected.size() != intent.duePeriodIds.size()) continue;
      boolean allPaid = selected.stream().allMatch(d -> d.amountPaid >= d.amountDue);
      boolean anyPaid = selected.stream().anyMatch(d -> d.amountPaid > 0);
      IntentStatus next = allPaid ? IntentStatus.FULFILLED : anyPaid ? IntentStatus.PARTIAL : IntentStatus.PENDING;
      if (intent.status != next) { intent.status = next; intents.save(intent); }
    }
  }
}
