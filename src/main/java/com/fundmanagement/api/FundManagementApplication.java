package com.fundmanagement.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.fundmanagement.api.config.FundProperties;

@SpringBootApplication
@EnableConfigurationProperties(FundProperties.class)
public class FundManagementApplication {
  public static void main(String[] args) {
    SpringApplication.run(FundManagementApplication.class, args);
  }
}
