package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.GmailPollingLeaseDocument;
import com.fundmanagement.api.config.GmailProperties;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Mongo-backed lease with an atomic compare-and-set acquisition and owner/token guarded release. */
@Service
public class GmailPollingLeaseService {
  private static final String LEASE_ID = "gmail-polling";
  private final MongoTemplate mongo; private final GmailProperties properties; private final String owner;
  @Autowired public GmailPollingLeaseService(MongoTemplate mongo, GmailProperties properties) { this(mongo, properties, UUID.randomUUID().toString()); }
  GmailPollingLeaseService(MongoTemplate mongo, GmailProperties properties, String owner) { this.mongo = mongo; this.properties = properties; this.owner = owner; }

  public Optional<LeaseHandle> tryAcquire() {
    Instant now = Instant.now(); Instant expires = now.plusSeconds(Math.max(60, properties.getLeaseDurationSeconds())); String token = UUID.randomUUID().toString();
    Criteria available = new Criteria().orOperator(Criteria.where("expiresAt").lte(now), Criteria.where("expiresAt").exists(false));
    Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(LEASE_ID), available));
    Update update = new Update().set("owner", owner).set("leaseToken", token).set("expiresAt", expires).set("acquiredAt", now).unset("releasedAt");
    try { GmailPollingLeaseDocument result = mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), GmailPollingLeaseDocument.class); if (result != null && owner.equals(result.owner) && token.equals(result.leaseToken)) return Optional.of(new LeaseHandle(owner, token)); return Optional.empty(); } catch (DuplicateKeyException race) { return Optional.empty(); }
  }

  public boolean release(LeaseHandle handle) {
    if (handle == null) return false;
    Instant now = Instant.now(); Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(LEASE_ID), Criteria.where("owner").is(handle.owner()), Criteria.where("leaseToken").is(handle.token()))); Update update = new Update().set("expiresAt", now).set("releasedAt", now); return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), GmailPollingLeaseDocument.class) != null;
  }

  public record LeaseHandle(String owner, String token) {}
}
