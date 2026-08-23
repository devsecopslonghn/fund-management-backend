package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.GmailCredentialDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GmailCredentialRepository extends MongoRepository<GmailCredentialDocument, String> {}
