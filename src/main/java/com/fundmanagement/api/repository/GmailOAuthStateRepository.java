package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.GmailOAuthStateDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GmailOAuthStateRepository extends MongoRepository<GmailOAuthStateDocument, String> {}
