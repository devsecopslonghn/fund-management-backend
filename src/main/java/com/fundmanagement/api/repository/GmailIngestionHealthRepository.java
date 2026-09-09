package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.GmailIngestionHealthDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GmailIngestionHealthRepository extends MongoRepository<GmailIngestionHealthDocument, String> {}
