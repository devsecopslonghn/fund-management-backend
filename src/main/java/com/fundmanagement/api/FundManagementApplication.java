package com.fundmanagement.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.fundmanagement.api.config.FundProperties;
import com.fundmanagement.api.config.GmailProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FundProperties.class, GmailProperties.class})
public class FundManagementApplication {
  public static void main(String[] args) {
    SpringApplication.run(FundManagementApplication.class, args);
  }
}
