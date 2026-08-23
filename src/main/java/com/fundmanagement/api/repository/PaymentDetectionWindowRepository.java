package com.fundmanagement.api.repository;

import com.fundmanagement.api.domain.PaymentDetectionWindowDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentDetectionWindowRepository extends MongoRepository<PaymentDetectionWindowDocument, String> {}
