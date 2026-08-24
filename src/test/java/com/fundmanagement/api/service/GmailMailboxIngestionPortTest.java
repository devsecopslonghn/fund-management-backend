package com.fundmanagement.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GmailMailboxIngestionPortTest {
  @Test void searchAlwaysContainsTheConfiguredReceivingAccount() {
    String query = GmailMailboxIngestionPort.gmailSearchQuery("000 123-456");
    assertThat(query).isEqualTo("(\"Sacombank\" OR \"Ngân hàng TMCP Sài Gòn Thương Tín\" OR from:sacombank.com.vn) \"000123456\"");
    assertThat(query).doesNotContainIgnoringCase("unread");
  }

  @Test void searchFailsClosedWithoutAConfiguredReceivingAccount() {
    assertThatThrownBy(() -> GmailMailboxIngestionPort.gmailSearchQuery("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
