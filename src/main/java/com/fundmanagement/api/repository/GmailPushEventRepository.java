package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.GmailPushEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GmailPushEventRepository extends MongoRepository<GmailPushEventDocument, String> {}
