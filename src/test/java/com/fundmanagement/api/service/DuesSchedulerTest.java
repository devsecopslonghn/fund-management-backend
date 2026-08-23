package com.fundmanagement.api.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fundmanagement.api.dto.ApiDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DuesSchedulerTest {
  @Mock DuesService dues; @Mock SettingsService settings;
  @Test void doesNotMutateWhenSchedulerIsDisabled() {
    when(settings.get()).thenReturn(new ApiDtos.SettingsResponse(100_000, .85, "bank", "code", "account", "holder", false, false, false, false, "sender", false, "", 587, ""));
    new DuesScheduler(dues, settings).generateCurrentMonth();
    org.mockito.Mockito.verifyNoInteractions(dues);
  }
  @Test void delegatesCurrentMonthWhenEnabled() {
    when(settings.get()).thenReturn(new ApiDtos.SettingsResponse(100_000, .85, "bank", "code", "account", "holder", true, false, false, false, "sender", false, "", 587, ""));
    new DuesScheduler(dues, settings).generateCurrentMonth();
    verify(dues).generate(org.mockito.ArgumentMatchers.matches("\\d{4}-\\d{2}"));
  }
}
