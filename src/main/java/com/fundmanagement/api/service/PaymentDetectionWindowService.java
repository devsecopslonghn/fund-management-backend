package com.fundmanagement.api.service;

import com.fundmanagement.api.domain.PaymentDetectionWindowDocument;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Mongo-backed singleton window. $max prevents a concurrent intent from shortening it. */
@Service
public class PaymentDetectionWindowService {
  private static final String ID = "gmail-fast-polling";
  private final MongoTemplate mongo;

  @Autowired public PaymentDetectionWindowService(MongoTemplate mongo) { this.mongo = mongo; }

  public void open(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) return;
    Instant now = Instant.now();
    Query query = Query.query(Criteria.where("_id").is(ID));
    Update update = new Update().max("activeUntil", now.plus(duration)).set("updatedAt", now);
    mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), PaymentDetectionWindowDocument.class);
  }

  public boolean isActive() {
    PaymentDetectionWindowDocument window = mongo.findById(ID, PaymentDetectionWindowDocument.class);
    return window != null && window.activeUntil != null && window.activeUntil.isAfter(Instant.now());
  }
}
