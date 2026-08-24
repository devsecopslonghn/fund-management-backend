package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GmailMailboxIngestionPortTest {
  @Test void searchAlwaysContainsTheConfiguredReceivingAccount() {
    assertThat(GmailMailboxIngestionPort.gmailSearchQuery("alerts@example.test", "000 123-456"))
        .isEqualTo("is:unread from:alerts@example.test \"000123456\"");
  }

  @Test void searchFailsClosedWithoutAConfiguredReceivingAccount() {
    assertThatThrownBy(() -> GmailMailboxIngestionPort.gmailSearchQuery("alerts@example.test", "---"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
