package com.fundmanagement.api.ops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundmanagement.api.dto.MemberDuesRepairDtos;
import com.fundmanagement.api.repository.*;
import com.fundmanagement.api.service.*;
import com.mongodb.client.MongoClients;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/** Offline maintenance entrypoint: no HTTP server, scheduler, ingestion or index creation. */
public final class MemberDuesRepairCommand {
  private MemberDuesRepairCommand() {}

  public static void main(String[] args) throws Exception {
    ((Logger) LoggerFactory.getLogger("org.mongodb.driver")).setLevel(Level.WARN);
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    MemberDuesRepairDtos.Request request = mapper.readValue(System.in, MemberDuesRepairDtos.Request.class);
    try (var validation = Validation.buildDefaultValidatorFactory()) {
      if (!validation.getValidator().validate(request).isEmpty()) throw new IllegalArgumentException("Invalid repair request");
    }
    String uri = System.getenv("MONGODB_URI");
    String database = System.getenv("MONGODB_DATABASE");
    String actor = System.getenv("FUND_REPAIR_ACTOR");
    if (uri == null || database == null || actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("MONGODB_URI, MONGODB_DATABASE and FUND_REPAIR_ACTOR are required");
    }
    try (var client = MongoClients.create(uri)) {
      var factory = new SimpleMongoClientDatabaseFactory(client, database);
      var mongo = new MongoTemplate(factory);
      var repositories = new MongoRepositoryFactory(mongo);
      var members = repositories.getRepository(MemberRepository.class);
      var dues = repositories.getRepository(DuePeriodRepository.class);
      var payments = repositories.getRepository(ManualPaymentRepository.class);
      var banks = repositories.getRepository(BankTransactionRepository.class);
      var audit = new AuditService(repositories.getRepository(AuditLogRepository.class), banks, payments, members);
      var memberService = new MemberService(members, audit);
      var repairs = new MemberDuesRepairService(members, dues, payments, banks, repositories.getRepository(PaymentIntentRepository.class),
          new AnnualDuePlanService(dues, memberService, audit), new FinancialTransactions(factory), audit, mapper, mongo);
      var selected = members.findByStatusAndMemberCode(com.fundmanagement.api.domain.MemberStatus.ACTIVE,
          request.memberCode().toUpperCase(java.util.Locale.ROOT));
      if (selected.size() != 1) throw new IllegalArgumentException("One active member is required");
      String memberId = selected.getFirst().id;
      Map<String, Object> before = ledgerState(mongo, memberId);
      if (!request.previewOnly()) {
        if (args.length != 1) throw new IllegalArgumentException("Apply requires a new snapshot file path");
        verifyUniqueIndex(mongo, "due_periods", new Document("memberId", 1).append("month", 1));
        verifyUniqueIndex(mongo, "manual_payments", new Document("idempotencyKey", 1));
        var matches = members.findByStatusAndMemberCode(com.fundmanagement.api.domain.MemberStatus.ACTIVE,
            request.memberCode().toUpperCase(java.util.Locale.ROOT));
        if (matches.size() != 1) throw new IllegalArgumentException("One active member is required");
        saveSnapshot(mongo, matches.getFirst().id, Path.of(args[0]));
      }
      var result = repairs.repair(request, actor);
      System.out.println(mapper.writeValueAsString(Map.of("repair", result,
          "ledgerBefore", before, "ledgerAfter", ledgerState(mongo, memberId))));
    }
  }

  private static Map<String, Object> ledgerState(MongoTemplate mongo, String memberId) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (String collection : List.of("members", "due_periods", "manual_payments", "bank_transactions")) {
      String field = collection.equals("members") ? "_id" : collection.equals("bank_transactions") ? "matchedMemberId" : "memberId";
      Object value = collection.equals("members") && org.bson.types.ObjectId.isValid(memberId)
          ? new org.bson.types.ObjectId(memberId) : memberId;
      for (Document document : mongo.getCollection(collection)
          .find(new Document(field, new Document("$ne", value))).sort(new Document("_id", 1))) {
        digest.update(collection.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(document.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
    }
    long income = sum(mongo, "manual_payments", new Document("reversed", new Document("$ne", true)));
    long expense = sum(mongo, "expenses", new Document("deleted", false));
    return Map.of("income", income, "expense", expense, "balance", Math.subtractExact(income, expense),
        "paymentCount", mongo.getCollection("manual_payments").countDocuments(),
        "bankCount", mongo.getCollection("bank_transactions").countDocuments(),
        "otherMembersDigest", HexFormat.of().formatHex(digest.digest()));
  }

  private static long sum(MongoTemplate mongo, String collection, Document filter) {
    long total = 0;
    for (Document row : mongo.getCollection(collection).find(filter).projection(new Document("amount", 1))) {
      total = Math.addExact(total, ((Number) row.get("amount")).longValue());
    }
    return total;
  }

  private static void verifyUniqueIndex(MongoTemplate mongo, String collection, Document key) {
    for (Document index : mongo.getCollection(collection).listIndexes()) {
      if (key.equals(index.get("key")) && Boolean.TRUE.equals(index.getBoolean("unique"))) return;
    }
    throw new IllegalStateException("Required unique index missing for " + collection);
  }

  private static void saveSnapshot(MongoTemplate mongo, String memberId, Path path) throws Exception {
    Document snapshot = new Document("memberId", memberId);
    for (String collection : List.of("due_periods", "manual_payments", "payment_intents")) {
      snapshot.put(collection, mongo.getCollection(collection).find(new Document("memberId", memberId)).into(new ArrayList<>()));
    }
    // Financial linkage only: never export contacts, bank account data or raw messages.
    snapshot.put("bank_transactions", mongo.getCollection("bank_transactions")
        .find(new Document("matchedMemberId", memberId))
        .projection(new Document("_id", 1).append("matchedDuePeriodIds", 1)
            .append("amount", 1).append("reconciliationStatus", 1))
        .into(new ArrayList<>()));
    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(path, snapshot.toJson());
  }
}
