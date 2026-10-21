package com.fundmanagement.api.controller;

import com.fundmanagement.api.dto.MemberDuesRepairDtos;
import com.fundmanagement.api.service.MemberDuesRepairService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberDuesRepairController {
  private final MemberDuesRepairService repairs;
  public MemberDuesRepairController(MemberDuesRepairService repairs) { this.repairs = repairs; }

  @PostMapping("/api/v1/admin/migrations/member-dues/repair")
  public MemberDuesRepairDtos.Response repair(@Valid @RequestBody MemberDuesRepairDtos.Request request,
      Principal principal) {
    return repairs.repair(request, principal.getName());
  }
}
