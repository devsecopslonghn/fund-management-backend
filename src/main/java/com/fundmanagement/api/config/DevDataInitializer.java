package com.fundmanagement.api.config;

import com.fundmanagement.api.domain.MemberStatus;
import com.fundmanagement.api.dto.ApiDtos;
import com.fundmanagement.api.repository.MemberRepository;
import com.fundmanagement.api.service.DuesService;
import com.fundmanagement.api.service.MemberService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Creates only synthetic, idempotent data for local development; it is never active in normal deployments. */
@Configuration
@Profile("dev")
public class DevDataInitializer {
  @Bean CommandLineRunner devData(MemberRepository members, MemberService memberService, DuesService duesService) {
    return args -> {
      if (members.findByStatus(MemberStatus.ACTIVE).stream().anyMatch(member -> "DEMO01".equals(member.memberCode))) return;
      memberService.create(new ApiDtos.MemberUpsertRequest("DEMO01", "Demo Member", "demo.member@example.invalid", null, "2026-01", null, 100_000, MemberStatus.ACTIVE), "dev-fixture");
      duesService.generate("2026-01"); duesService.generate("2026-02"); duesService.generate("2026-03");
    };
  }
}
