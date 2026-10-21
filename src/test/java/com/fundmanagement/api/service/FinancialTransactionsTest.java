package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.mongodb.MongoException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

class FinancialTransactionsTest {
  @Test void retriesAbortedConflictsButNeverReplaysAnUncertainCommit() {
    MongoException aborted = new MongoException(112, "synthetic write conflict");
    aborted.addLabel("TransientTransactionError");
    assertThat(FinancialTransactions.retryable(new RuntimeException(aborted))).isTrue();
    assertThat(FinancialTransactions.retryable(new DuplicateKeyException("synthetic"))).isTrue();
    assertThat(FinancialTransactions.retryable(new OptimisticLockingFailureException("synthetic"))).isTrue();
    MongoException uncertain = new MongoException(91, "synthetic uncertain commit");
    uncertain.addLabel("UnknownTransactionCommitResult");
    uncertain.addLabel("TransientTransactionError");
    assertThat(FinancialTransactions.retryable(new RuntimeException(uncertain))).isFalse();
    assertThat(FinancialTransactions.retryable(new IllegalStateException("stale preview"))).isFalse();
  }
}
