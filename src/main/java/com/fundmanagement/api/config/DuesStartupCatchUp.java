package com.fundmanagement.api.config;

import com.fundmanagement.api.service.DuesService;
import java.time.YearMonth;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in and bounded startup recovery; it only ensures the current month. */
@Configuration
public class DuesStartupCatchUp {
  @Bean
  CommandLineRunner currentMonthCatchUp(DuesService dues, com.fundmanagement.api.service.SettingsService settings) {
    return args -> { if (settings.get().startupCatchUpEnabled()) dues.generate(YearMonth.now().toString()); };
  }
}
