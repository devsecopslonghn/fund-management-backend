package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.GmailWatchStateDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GmailWatchStateRepository extends MongoRepository<GmailWatchStateDocument, String> {}
