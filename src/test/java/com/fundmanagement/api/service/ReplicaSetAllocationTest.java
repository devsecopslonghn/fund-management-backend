package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.domain.*;
import com.fundmanagement.api.dto.MemberDuesRepairDtos;
import com.fundmanagement.api.repository.*;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in real replica-set tests. Refuses any non-loopback MongoDB target. */
@EnabledIfEnvironmentVariable(named = "FUND_TEST_MONGODB_URI", matches = ".+")
@SpringBootTest(properties = {"app.security.admin-username=test-admin",
    "app.security.admin-password=test-only-password", "app.fund.due-scheduler-enabled=false",
    "app.fund.startup-catch-up-enabled=false"})
class ReplicaSetAllocationTest {
  @DynamicPropertySource static void mongoProperties(DynamicPropertyRegistry properties) throws Exception {
    String uri = System.getenv("FUND_TEST_MONGODB_URI");
    ConnectionString connection = new ConnectionString(uri);
    if (connection.getHosts().size() != 1 || !connection.getHosts().getFirst().startsWith("127.0.0.1:")) {
      throw new IllegalArgumentException("Integration tests require a dedicated loopback MongoDB");
    }
    String host = connection.getHosts().getFirst();
    try (var client = MongoClients.create("mongodb://" + host + "/?directConnection=true")) {
      var admin = client.getDatabase("admin");
      try {
        admin.runCommand(new Document("replSetInitiate", new Document("_id", "quybpdev-test")
            .append("members", List.of(new Document("_id", 0).append("host", host)))));
      } catch (MongoCommandException initialized) {
        if (initialized.getErrorCode() != 23) throw initialized;
      }
      for (int attempt = 0; attempt < 80; attempt++) {
        if (Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) break;
        Thread.sleep(250);
      }
    }
    properties.add("spring.data.mongodb.uri", () -> uri);
    properties.add("spring.data.mongodb.database", () -> "fund_allocation_test_" + TEST_DATABASE);
  }
  private static final String TEST_DATABASE = UUID.randomUUID().toString().replace("-", "");
  @Autowired MemberRepository members;
  @Autowired DuePeriodRepository dues;
  @Autowired ManualPaymentRepository payments;
  @Autowired BankTransactionRepository banks;
  @Autowired PaymentIntentRepository intents;
  @Autowired AuditLogRepository audits;
  @Autowired PaymentService paymentService;
  @Autowired DuesService duesService;
  @Autowired MemberDuesRepairService repairs;
  @Autowired FinancialTransactions transactions;
  @Autowired MongoTemplate mongo;
  @Autowired ObjectMapper mapper;

  @BeforeEach void clearOnlyGeneratedTestDatabase() {
    assertThat(mongo.getDb().getName()).isEqualTo("fund_allocation_test_" + TEST_DATABASE);
    dues.deleteAll(); payments.deleteAll(); banks.deleteAll(); intents.deleteAll(); audits.deleteAll(); members.deleteAll();
  }

  @Test void directTransferAndWebsiteLookupProduceTheSameThreePaidMonths() {
    MemberDocument direct = member("DIRECT");
    due(direct, "2026-09", 100_000, 0); due(direct, "2026-10", 100_000, 0);
    MemberDocument website = member("WEBSITE");
    due(website, "2026-09", 100_000, 0); due(website, "2026-10", 100_000, 0);
    duesService.publicPaymentOptionsByMember(null, website.memberCode, 2026);
    var first = automatic(direct, "gmail:direct", 300_000);
    automatic(website, "gmail:website", 300_000);
    assertThat(first.unallocatedAmount()).isZero();
    assertThat(paidMonths(direct)).containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(paidMonths(website)).isEqualTo(paidMonths(direct));
    assertThat(dues.findByMemberIdOrderByMonthAsc(direct.id)).hasSize(7);
    automatic(direct, "gmail:direct", 300_000);
    assertThat(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc(direct.id)).hasSize(1);
    assertThat(paidMonths(direct)).hasSize(3);
  }

  @Test void previewsAndRepairsOnlyTargetCreditWithoutChangingIncomeOrExistingPayments() throws Exception {
    MemberDocument target = legacyFixture();
    MemberDocument other = member("UNTOUCHED");
    due(other, "2026-09", 200_000, 200_000);
    String otherBefore = mapper.writeValueAsString(dues.findByMemberIdOrderByMonthAsc(other.id));
    long auditCount = audits.count();
    String state = snapshot();
    var preview = repairs.repair(request(true, null), "operator");
    assertThat(preview.createdPeriods()).containsExactly("2026-08", "2026-11", "2026-12", "2026-13", "2026-14");
    assertThat(preview.creditBefore()).isEqualTo(100_000);
    assertThat(preview.creditAfter()).isZero();
    assertThat(preview.totalReceivedBefore()).isEqualTo(300_000);
    assertThat(snapshot()).isEqualTo(state); // Includes all document versions and audit count.
    assertThat(audits.count()).isEqualTo(auditCount);
    var applied = repairs.repair(request(false, preview.previewToken()), "operator");
    assertThat(applied.totalReceivedAfter()).isEqualTo(300_000);
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(payments.findAll()).singleElement().satisfies(p -> {
      assertThat(p.amount).isEqualTo(300_000); assertThat(p.unallocatedAmount).isZero();
      assertThat(p.allocations).hasSize(3);
    });
    assertThat(banks.findAll()).singleElement().satisfies(b -> assertThat(b.matchedDuePeriodIds).hasSize(3));
    assertThat(mapper.writeValueAsString(dues.findByMemberIdOrderByMonthAsc(other.id))).isEqualTo(otherBefore);
    assertThat(audits.findAll()).singleElement().satisfies(a -> assertThat(a.action).isEqualTo("MEMBER_DUES_REPAIRED"));
    var next = repairs.repair(request(true, null), "operator");
    String after = snapshot();
    repairs.repair(request(false, next.previewToken()), "operator");
    assertThat(snapshot()).isEqualTo(after);
    assertThatThrownBy(() -> repairs.repair(request(false, preview.previewToken()), "operator"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
  }

  @Test void stalePreviewAndMissingTokenCannotModifyAnything() throws Exception {
    MemberDocument target = legacyFixture();
    var preview = repairs.repair(request(true, null), "operator");
    DuePeriodDocument september = dues.findByMemberIdAndMonth(target.id, "2026-09").orElseThrow();
    september.notes = "Changed after preview"; dues.save(september);
    String before = snapshot();
    assertThatThrownBy(() -> repairs.repair(request(false, preview.previewToken()), "operator"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
    assertThatThrownBy(() -> repairs.repair(request(false, null), "operator"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test void failedTransactionRollsBackNewPeriodsLedgerAllocationsAndAudit() throws Exception {
    MemberDocument target = member("ROLLBACK");
    String before = snapshot();
    assertThatThrownBy(() -> transactions.run(() -> {
      automatic(target, "gmail:rollback", 300_000);
      throw new IllegalStateException("synthetic failure after allocation");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test void concurrentReplicasCannotDuplicateTheSamePaymentOrDue() throws Exception {
    MemberDocument target = member("CONCURRENT");
    try (var workers = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Object> work = () -> { start.await(); return automatic(target, "gmail:same", 300_000); };
      var one = workers.submit(work); var two = workers.submit(work); start.countDown();
      one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
    }
    assertThat(payments.findAll()).hasSize(1);
    assertThat(dues.findAll()).hasSize(7);
    assertThat(paidMonths(target)).hasSize(3);
  }

  @Test void partialAndExcessTransfersPreserveIntegerAmountsAndYearBoundary() {
    MemberDocument target = member("PARTIAL");
    var partial = automatic(target, "gmail:partial", 150_000);
    assertThat(partial.unallocatedAmount()).isZero();
    assertThat(dues.findByMemberIdAndMonth(target.id, "2026-09").orElseThrow().amountPaid).isEqualTo(50_000);
    var excess = automatic(target, "gmail:excess", 600_000);
    assertThat(excess.unallocatedAmount()).isEqualTo(50_000);
    assertThat(dues.findAll()).allMatch(d -> d.month.startsWith("2026-"));
    assertThat(payments.findAll().stream().mapToLong(p -> p.amount).sum()).isEqualTo(750_000);
  }

  @Test void lookupPreservesAdjustedDuesAndAllocatesOnlyExistingCredit() {
    MemberDocument target = legacyFixture();
    DuePeriodDocument september = dues.findByMemberIdAndMonth(target.id, "2026-09").orElseThrow();
    september.amountDue = 90_000; september.status = DueStatus.OVERPAID; dues.save(september);
    duesService.publicPaymentOptionsByMember(null, target.memberCode, 2026);
    DuePeriodDocument after = dues.findById(september.id).orElseThrow();
    assertThat(after.amountDue).isEqualTo(90_000);
    assertThat(after.amountPaid).isEqualTo(100_000);
    assertThat(after.payments).singleElement().satisfies(a -> assertThat(a.amount).isEqualTo(100_000));
    assertThat(payments.findAll().getFirst().unallocatedAmount).isZero();
    assertThat(banks.findAll().getFirst().matchedDuePeriodIds).hasSize(3);
  }

  @Test void explicitQrSelectionsStayOnTheirChosenPeriodsAndCanBeReversed() {
    MemberDocument target = member("SELECTED");
    DuePeriodDocument august = due(target, "2026-08", 100_000, 0);
    DuePeriodDocument october = due(target, "2026-10", 100_000, 0);
    PaymentIntentDocument intent = new PaymentIntentDocument(); intent.memberId = target.id;
    intent.duePeriodIds = List.of(october.id); intent.totalAmount = 100_000; intent.status = IntentStatus.PENDING;
    intents.insert(intent);
    var response = paymentService.recordForIntent(intent, 100_000, Instant.parse("2026-10-21T01:14:00Z"), "gmail:selected", "test");
    assertThat(dues.findById(august.id).orElseThrow().amountPaid).isZero();
    assertThat(dues.findById(october.id).orElseThrow().amountPaid).isEqualTo(100_000);
    paymentService.reverse(response.id(), "test", "Synthetic reversal");
    assertThat(dues.findById(october.id).orElseThrow().amountPaid).isZero();
    assertThat(payments.findById(response.id()).orElseThrow().reversed).isTrue();
    assertThat(intents.findById(intent.id).orElseThrow().status).isEqualTo(IntentStatus.PENDING);
  }

  @Test void importedSeedsAndEffectiveRatesRemainSeparateFromLedgerIncome() {
    MemberDocument target = member("IMPORTED");
    target.initiallyPaidMonths = List.of("2026-08");
    target.contributionRates = new ArrayList<>(List.of(new ContributionRate(100_000, "2026-08", "2026-09"),
        new ContributionRate(200_000, "2026-10", null)));
    members.save(target);
    automatic(target, "gmail:imported", 300_000);
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(dues.findByMemberIdAndMonth(target.id, "2026-08").orElseThrow().seededPaid).isTrue();
    assertThat(payments.findAll()).singleElement().satisfies(p -> assertThat(p.amount).isEqualTo(300_000));
  }

  @Test void repairDoesNotAllocateIntentOrReversedCreditAndRefreshesPendingIntentStatuses() {
    MemberDocument target = legacyFixture();
    DuePeriodDocument august = due(target, "2026-08", 100_000, 0);
    PaymentIntentDocument pending = new PaymentIntentDocument(); pending.memberId = target.id;
    pending.duePeriodIds = List.of(august.id); pending.totalAmount = 100_000; pending.status = IntentStatus.PENDING;
    intents.insert(pending);
    for (String mode : List.of("INTENT", "REVERSED")) {
      ManualPaymentDocument extra = new ManualPaymentDocument(); extra.memberId = target.id;
      extra.amount = 100_000; extra.unallocatedAmount = 100_000; extra.idempotencyKey = "synthetic:" + mode;
      extra.allocationMode = mode.equals("INTENT") ? "INTENT" : "FIFO"; extra.reversed = mode.equals("REVERSED");
      extra.occurredAt = Instant.parse("2026-10-21T01:14:00Z"); payments.insert(extra);
    }
    var preview = repairs.repair(request(true, null), "test");
    var response = repairs.repair(request(false, preview.previewToken()), "test");
    assertThat(response.creditBefore()).isEqualTo(200_000); assertThat(response.creditAfter()).isEqualTo(100_000);
    assertThat(intents.findById(pending.id).orElseThrow().status).isEqualTo(IntentStatus.FULFILLED);
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10");
  }

  @Test void existingCreditIsUsedBeforeTheNextIncomingTransfer() {
    MemberDocument target = legacyFixture();
    automatic(target, "gmail:next", 100_000);
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10", "2026-11");
    assertThat(payments.findAll()).allMatch(p -> p.unallocatedAmount == 0);
    assertThat(payments.findByIdempotencyKey("gmail:synthetic-target").orElseThrow().allocations).hasSize(3);
  }

  @Test void pausedMembersDoNotAccrueNewAnnualPeriods() {
    MemberDocument target = member("PAUSED"); target.status = MemberStatus.PAUSED; members.save(target);
    assertThatThrownBy(() -> automatic(target, "gmail:paused", 300_000)).isInstanceOf(IllegalStateException.class);
    assertThat(dues.findAll()).isEmpty(); assertThat(payments.findAll()).isEmpty();
  }

  @Test void offlineCommandUsesSamePreviewAndApplyWithoutStartingTheApp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    MemberDocument target = legacyFixture();
    var preview = command(request(true, null), null, temp);
    String token = preview.path("repair").path("previewToken").asText();
    java.nio.file.Path backup = temp.resolve("before.json");
    var applied = command(request(false, token), backup, temp);
    assertThat(applied.path("ledgerBefore")).isEqualTo(applied.path("ledgerAfter"));
    assertThat(java.nio.file.Files.exists(backup)).isTrue();
    assertThat(java.nio.file.Files.getPosixFilePermissions(backup))
        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10");
  }

  private com.fasterxml.jackson.databind.JsonNode command(MemberDuesRepairDtos.Request request,
      java.nio.file.Path backup, java.nio.file.Path temp) throws Exception {
    List<String> args = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp",
        System.getProperty("java.class.path"), "com.fundmanagement.api.ops.MemberDuesRepairCommand"));
    if (backup != null) args.add(backup.toString());
    ProcessBuilder builder = new ProcessBuilder(args);
    builder.environment().put("MONGODB_URI", System.getenv("FUND_TEST_MONGODB_URI"));
    builder.environment().put("MONGODB_DATABASE", mongo.getDb().getName());
    builder.environment().put("FUND_REPAIR_ACTOR", "test:offline-command");
    java.nio.file.Path stderr = temp.resolve("stderr.txt"); builder.redirectError(stderr.toFile());
    Process process = builder.start();
    try (var stdin = process.getOutputStream()) { stdin.write(mapper.writeValueAsBytes(request)); }
    byte[] stdout = process.getInputStream().readAllBytes();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).withFailMessage(java.nio.file.Files.readString(stderr)).isZero();
    return mapper.readTree(stdout);
  }

  @Test void bankMatchAndPaymentCommitTogetherAndRollbackTogether() {
    MemberDocument target = member("MAILTARGET");
    IngestionService success = ingestion(false, target);
    success.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest("synthetic-success", "synthetic"));
    assertThat(paidMonths(target)).containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(banks.findByProviderMessageId("synthetic-success").orElseThrow().reconciliationStatus).isEqualTo("MATCHED");
    assertThat(banks.findByProviderMessageId("synthetic-success").orElseThrow().matchedDuePeriodIds).hasSize(3);
    success.ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest("synthetic-success", "synthetic"));
    assertThat(payments.findAll()).hasSize(1);
    MemberDocument failing = member("MAILFAIL");
    long incomeBefore = payments.findAll().stream().mapToLong(p -> p.amount).sum();
    ingestion(true, failing).ingest(new com.fundmanagement.api.dto.ApiDtos.BankTransactionIngestionRequest("synthetic-failure", "synthetic"));
    assertThat(dues.findByMemberIdOrderByMonthAsc(failing.id)).isEmpty();
    assertThat(payments.findByMemberIdOrderByOccurredAtAscCreatedAtAsc(failing.id)).isEmpty();
    assertThat(banks.findByProviderMessageId("synthetic-failure").orElseThrow().reconciliationStatus).isEqualTo("NEEDS_REVIEW");
    assertThat(payments.findAll().stream().mapToLong(p -> p.amount).sum()).isEqualTo(incomeBefore);
  }

  private IngestionService ingestion(boolean failAudit, MemberDocument member) {
    var settings = org.mockito.Mockito.mock(SettingsService.class);
    org.mockito.Mockito.when(settings.ingestionStatus()).thenReturn(new com.fundmanagement.api.dto.ApiDtos.IngestionStatusResponse(true, false, false, "synthetic", "test"));
    org.mockito.Mockito.when(settings.get()).thenReturn(new com.fundmanagement.api.dto.ApiDtos.SettingsResponse(100_000, .85,
        "Synthetic", "000000", "123456789", "Synthetic", false, false, true, false, "synthetic", false, "", 587, ""));
    var parser = org.mockito.Mockito.mock(SacombankMessageParser.class);
    org.mockito.Mockito.when(parser.parse("synthetic")).thenReturn(new SacombankMessageParser.ParsedBankMessage(
        Instant.parse("2026-10-21T01:14:00Z"), 300_000, "IN", "123456789", "Userid " + member.memberCode, 900_000L));
    var audit = mongoAudit();
    if (failAudit) {
      audit = org.mockito.Mockito.spy(audit);
      org.mockito.Mockito.doThrow(new IllegalStateException("synthetic audit failure")).when(audit).record(
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("BANK_TRANSACTION_AUTO_MATCHED"),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap());
    }
    var references = new org.springframework.data.mongodb.repository.support.MongoRepositoryFactory(mongo).getRepository(PaymentReferenceRepository.class);
    IngestionService service = new IngestionService(banks, parser, settings, intents, paymentService, audit,
        new com.fundmanagement.api.config.GmailProperties(), mongo, references, members);
    service.setFinancialTransactions(transactions);
    return service;
  }
  private AuditService mongoAudit() { return new AuditService(audits, banks, payments, members); }

  @Test void distinctConcurrentTransfersShareOnePlanWithoutOverwritingEachOther() throws Exception {
    MemberDocument target = member("TWOPAYMENTS");
    try (var workers = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      var one = workers.submit(() -> { start.await(); return automatic(target, "gmail:first", 300_000); });
      var two = workers.submit(() -> { start.await(); return automatic(target, "gmail:second", 300_000); });
      start.countDown(); one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
    }
    assertThat(payments.findAll()).hasSize(2);
    assertThat(dues.findAll()).hasSize(7);
    assertThat(dues.findAll().stream().mapToLong(d -> d.amountPaid).sum()).isEqualTo(600_000);
  }

  @Test void repairMergesAnExistingPartialAllocationInsteadOfDuplicatingItsDueId() {
    MemberDocument target = member("ANPH44225");
    DuePeriodDocument august = due(target, "2026-08", 100_000, 50_000);
    ManualPaymentDocument p = new ManualPaymentDocument(); p.memberId = target.id; p.amount = 150_000;
    p.idempotencyKey = "synthetic:partial-credit"; p.unallocatedAmount = 100_000;
    p.allocations.add(new ManualPaymentDocument.ManualAllocation(august.id, 50_000)); payments.insert(p);
    august.payments.add(new PaymentAllocation(p.id, 50_000, Instant.now(), "manual")); dues.save(august);
    var preview = repairs.repair(request(true, null), "test");
    repairs.repair(request(false, preview.previewToken()), "test");
    ManualPaymentDocument after = payments.findById(p.id).orElseThrow();
    assertThat(after.unallocatedAmount).isZero();
    assertThat(after.allocations).hasSize(2);
    assertThat(after.allocations.stream().filter(a -> a.duePeriodId.equals(august.id))).singleElement()
        .satisfies(a -> assertThat(a.amount).isEqualTo(100_000));
    paymentService.reverse(after.id, "test", "Verify merged allocation reversal");
    assertThat(dues.findAll()).allMatch(d -> d.amountPaid == 0);
  }

  @Test void repairRetainsUnknownHistoricalMetadata() {
    MemberDocument target = legacyFixture();
    var p = payments.findAll().getFirst();
    mongo.getCollection("manual_payments").updateOne(new Document("_id", new org.bson.types.ObjectId(p.id)), new Document("$set", new Document("legacyProvenance", "retain")));
    var b = banks.findAll().getFirst();
    mongo.getCollection("bank_transactions").updateOne(new Document("_id", new org.bson.types.ObjectId(b.id)), new Document("$set", new Document("legacyProvenance", "retain")));
    var preview = repairs.repair(request(true, null), "test");
    repairs.repair(request(false, preview.previewToken()), "test");
    assertThat(mongo.getCollection("manual_payments").find(new Document("_id", new org.bson.types.ObjectId(p.id))).first().getString("legacyProvenance")).isEqualTo("retain");
    assertThat(mongo.getCollection("bank_transactions").find(new Document("_id", new org.bson.types.ObjectId(b.id))).first().getString("legacyProvenance")).isEqualTo("retain");
  }

  private MemberDocument member(String code) {
    MemberDocument m = new MemberDocument(); m.memberCode = code; m.fullName = "Synthetic " + code;
    m.normalizedFullName = m.fullName.toLowerCase(); m.status = MemberStatus.ACTIVE;
    m.joinMonth = "2026-07"; m.baselineMonth = "2026-08"; m.contributionStartMonth = "2026-08";
    m.annualContributionMonths = 14; m.contributionRates.add(new ContributionRate(100_000, "2026-08", null));
    return members.insert(m);
  }
  private DuePeriodDocument due(MemberDocument m, String month, long amount, long paid) {
    DuePeriodDocument d = new DuePeriodDocument(); d.memberId = m.id; d.month = month;
    d.amountDue = amount; d.amountPaid = paid; d.status = paid == 0 ? DueStatus.UNPAID : DueStatus.PAID;
    return dues.insert(d);
  }
  private MemberDocument legacyFixture() {
    MemberDocument m = member("ANPH44225");
    DuePeriodDocument september = due(m, "2026-09", 100_000, 100_000);
    DuePeriodDocument october = due(m, "2026-10", 100_000, 100_000);
    ManualPaymentDocument p = new ManualPaymentDocument(); p.memberId = m.id; p.amount = 300_000;
    p.unallocatedAmount = 100_000; p.idempotencyKey = "gmail:synthetic-target";
    p.occurredAt = Instant.parse("2026-10-21T01:14:00Z"); p.createdAt = p.occurredAt;
    p.allocations.add(new ManualPaymentDocument.ManualAllocation(september.id, 100_000));
    p.allocations.add(new ManualPaymentDocument.ManualAllocation(october.id, 100_000));
    payments.insert(p);
    for (DuePeriodDocument d : List.of(september, october)) {
      d.payments.add(new PaymentAllocation(p.id, 100_000, p.occurredAt, "automatic")); dues.save(d);
    }
    BankTransactionDocument b = new BankTransactionDocument(); b.providerMessageId = "synthetic-target";
    b.matchedMemberId = m.id; b.amount = 300_000; b.reconciliationStatus = "MATCHED";
    b.matchedDuePeriodIds = List.of(september.id, october.id); banks.insert(b);
    return m;
  }
  private com.fundmanagement.api.dto.ApiDtos.ManualPaymentResponse automatic(MemberDocument m, String key, long amount) {
    return paymentService.recordAutomatic(m.id, amount, Instant.parse("2026-10-21T01:14:00Z"), key, 2026, "test");
  }
  private List<String> paidMonths(MemberDocument m) {
    return dues.findByMemberIdOrderByMonthAsc(m.id).stream().filter(d -> d.status == DueStatus.PAID).map(d -> d.month).toList();
  }
  private MemberDuesRepairDtos.Request request(boolean dryRun, String token) {
    return new MemberDuesRepairDtos.Request("ANPH44225", 2026, dryRun, "Repair missing August period", token);
  }
  private String snapshot() throws Exception {
    return mapper.writeValueAsString(List.of(members.findAll(), dues.findAll(), payments.findAll(), banks.findAll(), audits.findAll()));
  }
}
