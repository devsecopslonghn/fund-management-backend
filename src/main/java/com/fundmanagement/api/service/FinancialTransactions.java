package com.fundmanagement.api.service;

import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import java.util.function.Supplier;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retry the complete transaction, reloading documents on every attempt. */
@Component
public class FinancialTransactions {
  private final TransactionTemplate template;

  public FinancialTransactions(MongoDatabaseFactory factory) {
    template = new TransactionTemplate(new MongoTransactionManager(factory,
        TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).build()));
  }

  public <T> T run(Supplier<T> work) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) return work.get();
    for (int attempt = 0; ; attempt++) {
      try {
        return template.execute(status -> work.get());
      } catch (RuntimeException failure) {
        if (attempt >= 4 || !retryable(failure)) throw failure;
        // Give the competing replica time to commit before opening a fresh snapshot.
        long delayMillis = 25L * (attempt + 1) + java.util.concurrent.ThreadLocalRandom.current().nextLong(25);
        java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delayMillis));
        if (Thread.currentThread().isInterrupted()) throw failure;
      }
    }
  }

  static boolean retryable(Throwable failure) {
    boolean retry = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof MongoException mongo) {
        // Never replay work when the server may already have committed it.
        if (mongo.hasErrorLabel("UnknownTransactionCommitResult")) return false;
        retry |= mongo.hasErrorLabel("TransientTransactionError");
      }
      retry |= cause instanceof DuplicateKeyException || cause instanceof OptimisticLockingFailureException;
    }
    return retry;
  }
}
