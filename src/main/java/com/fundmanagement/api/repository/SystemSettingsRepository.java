package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.SystemSettingsDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SystemSettingsRepository extends MongoRepository<SystemSettingsDocument, String> {}
