package com.fundmanagement.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.domain.*;
import com.fundmanagement.api.dto.MemberDuesRepairDtos;
import com.fundmanagement.api.repository.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;

/** Single-member preview/apply boundary. Existing paid allocations are never moved. */
@Service
public class MemberDuesRepairService {
  private final MemberRepository members;
  private final DuePeriodRepository dues;
  private final ManualPaymentRepository payments;
  private final BankTransactionRepository banks;
  private final PaymentIntentRepository intents;
  private final AnnualDuePlanService plans;
  private final FinancialTransactions transactions;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final MongoTemplate mongo;

  public MemberDuesRepairService(MemberRepository members, DuePeriodRepository dues,
      ManualPaymentRepository payments, BankTransactionRepository banks, PaymentIntentRepository intents, AnnualDuePlanService plans,
      FinancialTransactions transactions, AuditService audit, ObjectMapper mapper, MongoTemplate mongo) {
    this.members = members; this.dues = dues; this.payments = payments; this.banks = banks;
    this.intents = intents; this.plans = plans; this.transactions = transactions; this.audit = audit; this.mongo = mongo;
    this.mapper = mapper.copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public MemberDuesRepairDtos.Response repair(MemberDuesRepairDtos.Request request, String actor) {
    return transactions.run(() -> repairInTransaction(request, actor));
  }

  private MemberDuesRepairDtos.Response repairInTransaction(MemberDuesRepairDtos.Request request, String actor) {
    ContributionPeriod.yearPeriods(request.year());
    List<MemberDocument> matches = members.findByStatusAndMemberCode(MemberStatus.ACTIVE,
        request.memberCode().toUpperCase(Locale.ROOT));
    if (matches.size() != 1) throw new IllegalArgumentException("One active member is required");
    MemberDocument member = matches.getFirst();
    List<DuePeriodDocument> beforeDues = dues.findByMemberIdOrderByMonthAsc(member.id).stream()
        .sorted(Comparator.comparing(d -> d.month, ContributionPeriod.comparator())).toList();
    List<ManualPaymentDocument> beforePayments = payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc(member.id)
        .stream().sorted(Comparator.comparing(p -> p.id)).toList();
    List<BankTransactionDocument> bankRows = banks.findByMatchedMemberId(member.id).stream()
        .sorted(Comparator.comparing(t -> t.id)).toList();
    List<PaymentIntentDocument> beforeIntents = intents.findByMemberId(member.id).stream()
        .sorted(Comparator.comparing(i -> i.id)).toList();
    validateLedger(beforeDues, beforePayments);
    String token = fingerprint(member, request.year(), beforeDues, beforePayments, bankRows, beforeIntents);
    if (!request.previewOnly() && (request.previewToken() == null || !MessageDigest.isEqual(
        token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        request.previewToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))) {
      throw new IllegalStateException("Repair preview is missing or stale; request a new dry run");
    }

    // Preview uses detached copies and the exact same pure allocation function as apply.
    List<DuePeriodDocument> afterDues = beforeDues.stream().map(d -> copy(d, DuePeriodDocument.class))
        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    List<ManualPaymentDocument> afterPayments = beforePayments.stream()
        .map(p -> copy(p, ManualPaymentDocument.class)).toList();
    List<DuePeriodDocument> missing = plans.missing(member, request.year(), afterDues);
    List<String> created = missing.stream().map(d -> d.month).toList();
    for (DuePeriodDocument due : missing) {
      due.id = new org.bson.types.ObjectId().toHexString();
      afterDues.add(due);
    }
    List<ManualPaymentDocument> ordered = afterPayments.stream()
        .sorted(Comparator.comparing((ManualPaymentDocument p) -> p.occurredAt,
            Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(p -> p.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(p -> p.id)).toList();
    Map<String, DuePeriodDocument> eligible = new HashMap<>();
    for (DuePeriodDocument due : afterDues) {
      String start = member.contributionStartMonth == null || member.contributionStartMonth.isBlank()
          ? member.baselineMonth : member.contributionStartMonth;
      if (ContributionPeriod.inPlan(due.month, start, 14)) eligible.put(due.id, due);
    }
    Set<String> changedPayments = new HashSet<>();
    for (ManualPaymentDocument payment : ordered) {
      if (payment.reversed || "INTENT".equals(payment.allocationMode)
          || !PaymentAllocationYear.matches(payment, beforeDues, request.year())) continue;
      long creditBefore = payment.unallocatedAmount;
      FifoAllocator.allocateRemaining(payment, new ArrayList<>(eligible.values()), request.year(), Instant.now(), "repair");
      if (creditBefore != payment.unallocatedAmount) changedPayments.add(payment.id);
    }
    validateLedger(afterDues, afterPayments);
    Map<String, ManualPaymentDocument> paymentByKey = new HashMap<>();
    for (ManualPaymentDocument p : afterPayments) paymentByKey.put(p.idempotencyKey, p);
    List<BankTransactionDocument> changedBanks = new ArrayList<>();
    for (BankTransactionDocument bank : bankRows) {
      ManualPaymentDocument p = paymentByKey.get("gmail:" + bank.providerMessageId);
      if (p == null) p = paymentByKey.get("bank:" + bank.providerMessageId);
      if (p == null || p.reversed || !changedPayments.contains(p.id) || !"MATCHED".equals(bank.reconciliationStatus)) continue;
      if (p.amount != bank.amount) throw new IllegalStateException("Bank and payment amounts disagree");
      List<String> ids = p.allocations.stream().map(a -> a.duePeriodId).distinct().toList();
      if (!new HashSet<>(ids).equals(new HashSet<>(bank.matchedDuePeriodIds == null ? List.of() : bank.matchedDuePeriodIds))) {
        BankTransactionDocument detached = copy(bank, BankTransactionDocument.class);
        detached.matchedDuePeriodIds = ids; changedBanks.add(detached);
      }
    }
    Map<String, DuePeriodDocument> beforeByMonth = new HashMap<>();
    beforeDues.forEach(d -> beforeByMonth.put(d.month, d));
    List<MemberDuesRepairDtos.Period> periodChanges = afterDues.stream()
        .filter(d -> ContributionPeriod.year(d.month) == request.year())
        .sorted(Comparator.comparing(d -> d.month, ContributionPeriod.comparator()))
        .map(d -> new MemberDuesRepairDtos.Period(d.month, !beforeByMonth.containsKey(d.month), d.amountDue,
            beforeByMonth.containsKey(d.month) ? beforeByMonth.get(d.month).amountPaid : 0, d.amountPaid, d.status)).toList();
    Map<String, ManualPaymentDocument> beforeById = new HashMap<>();
    beforePayments.forEach(p -> beforeById.put(p.id, p));
    List<MemberDuesRepairDtos.Payment> paymentChanges = afterPayments.stream().filter(p -> !p.reversed)
        .map(p -> new MemberDuesRepairDtos.Payment(p.id, p.amount,
            beforeById.get(p.id).unallocatedAmount, p.unallocatedAmount)).toList();
    long income = totalReceived(beforePayments);
    if (income != totalReceived(afterPayments)) throw new IllegalStateException("Repair would change ledger income");
    if (!request.previewOnly()) {
      for (DuePeriodDocument due : afterDues) {
        DuePeriodDocument before = beforeByMonth.get(due.month);
        if (before == null) dues.insert(due);
        else if (before.amountPaid != due.amountPaid) {
          requireUpdated(mongo.updateFirst(Query.query(
              Criteria.where("_id").is(due.id).and("memberId").is(member.id)),
              new Update().set("amountPaid", due.amountPaid)
                  .set("status", due.status).set("payments", due.payments).inc("version", 1L), DuePeriodDocument.class));
        }
      }
      for (ManualPaymentDocument p : afterPayments) if (changedPayments.contains(p.id)) {
        requireUpdated(mongo.updateFirst(Query.query(
            Criteria.where("_id").is(p.id).and("memberId").is(member.id)),
            new Update().set("allocations", p.allocations)
                .set("unallocatedAmount", p.unallocatedAmount), ManualPaymentDocument.class));
      }
      for (BankTransactionDocument bank : changedBanks) {
        requireUpdated(mongo.updateFirst(Query.query(
            Criteria.where("_id").is(bank.id)
                .and("matchedMemberId").is(member.id).and("reconciliationStatus").is("MATCHED")),
            new Update().set("matchedDuePeriodIds", bank.matchedDuePeriodIds),
            BankTransactionDocument.class));
      }
      if (!created.isEmpty() || !changedPayments.isEmpty() || !changedBanks.isEmpty()) {
        IntentStatusProjector.refresh(member.id, dues, intents);
        audit.record(actor, "MEMBER_DUES_REPAIRED", "member", member.id,
            Map.of("previewToken", token, "credit", totalCredit(beforePayments)),
            Map.of("memberId", member.id, "year", request.year(), "reason", request.reason(),
                "createdPeriods", created, "credit", totalCredit(afterPayments), "income", income));
      }
    }
    return new MemberDuesRepairDtos.Response(member.memberCode, request.year(), request.previewOnly(), token,
        created, periodChanges, paymentChanges, income, income,
        totalCredit(beforePayments), totalCredit(afterPayments), changedBanks.size());
  }

  private void requireUpdated(UpdateResult result) {
    if (result.getMatchedCount() != 1) throw new IllegalStateException("Repair state changed; request a new preview");
  }

  private void validateLedger(List<DuePeriodDocument> periods, List<ManualPaymentDocument> ledger) {
    Map<String, DuePeriodDocument> byId = new HashMap<>();
    periods.forEach(d -> byId.put(d.id, d));
    Map<String, Long> totalsByDue = new HashMap<>();
    for (ManualPaymentDocument payment : ledger) {
      if (payment.reversed) continue;
      if (payment.allocations == null) throw new IllegalStateException("Payment allocations are missing");
      long allocated = 0;
      Set<String> seen = new HashSet<>();
      for (var allocation : payment.allocations) {
        if (allocation.amount <= 0 || !seen.add(allocation.duePeriodId)) throw new IllegalStateException("Invalid payment allocations");
        DuePeriodDocument due = byId.get(allocation.duePeriodId);
        if (due == null || !Objects.equals(due.memberId, payment.memberId)) throw new IllegalStateException("Allocation due period is missing");
        long linked = 0;
        if (due.payments != null) for (var item : due.payments) if (payment.id.equals(item.paymentId)) linked = Math.addExact(linked, item.amount);
        if (linked != allocation.amount || due.amountPaid < linked) throw new IllegalStateException("Allocation projections disagree");
        allocated = Math.addExact(allocated, allocation.amount);
        totalsByDue.merge(due.id, allocation.amount, Math::addExact);
      }
      if (payment.amount <= 0 || payment.unallocatedAmount < 0
          || Math.addExact(allocated, payment.unallocatedAmount) != payment.amount) throw new IllegalStateException("Payment balance is inconsistent");
    }
    for (var total : totalsByDue.entrySet()) {
      if (total.getValue() > byId.get(total.getKey()).amountPaid) throw new IllegalStateException("Due paid amount is below its ledger allocations");
    }
  }

  private long totalReceived(List<ManualPaymentDocument> rows) {
    long sum = 0; for (var p : rows) if (!p.reversed) sum = Math.addExact(sum, p.amount); return sum;
  }
  private long totalCredit(List<ManualPaymentDocument> rows) {
    long sum = 0; for (var p : rows) if (!p.reversed) sum = Math.addExact(sum, p.unallocatedAmount); return sum;
  }
  private <T> T copy(T value, Class<T> type) { return mapper.convertValue(value, type); }

  private String fingerprint(MemberDocument member, int year, List<DuePeriodDocument> periods,
      List<ManualPaymentDocument> ledger, List<BankTransactionDocument> bankRows, List<PaymentIntentDocument> intentRows) {
    // Deliberately excludes contact information and raw bank messages.
    Map<String, Object> plan = new TreeMap<>();
    plan.put("id", member.id); plan.put("year", year); plan.put("version", member.version);
    plan.put("baseline", member.baselineMonth); plan.put("start", member.contributionStartMonth);
    plan.put("rates", member.contributionRates); plan.put("seeds", member.initiallyPaidMonths);
    List<Object> bankState = bankRows.stream().map(b -> (Object) Arrays.asList(b.id, b.providerMessageId,
        b.amount, b.reconciliationStatus, b.matchedMemberId, b.matchedDuePeriodIds)).toList();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(mapper.writeValueAsBytes(List.of(plan, periods, ledger, bankState, intentRows))));
    } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
      throw new IllegalStateException("Cannot fingerprint repair state", ex);
    }
  }
}
