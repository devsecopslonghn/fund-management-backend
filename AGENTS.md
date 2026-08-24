# Fund Management Backend — Agent Guide

## Purpose and source of truth

This repository owns the Java/Spring Boot backend for the single-group Fund
Management System: REST API, business rules, MongoDB persistence, scheduled
jobs, Gmail/Sacombank ingestion, reconciliation, notifications, auditability,
and the published OpenAPI/Swagger contract.

Read `README.md` (the SRS) before changing behavior. The SRS is the product
source of truth; focus in particular on sections 3–8, 10, and 12. The frontend
repository consumes this repository's OpenAPI contract. Do not make a breaking
API or business-rule change without updating the contract and coordinating the
affected frontend work.

The frontend's visual source is Figma, but this repository must preserve its
server-side role, privacy, and data constraints rather than duplicating UI
logic.

## Domain invariants

- v1 has exactly one fund/group. Do not add multi-tenancy or a fund selector
  without an approved SRS change and data-migration plan.
- Member deletion is soft deletion only; historical dues, transactions,
  expenses, payments, and audit records must remain attributable and intact.
- Baseline month defaults to the month after join month and may be explicitly
  overridden. Paused and left periods must not accrue dues outside the allowed
  window.
- Generate one due period per eligible member/month. Enforce the unique
  `(member_id, month)` invariant and make scheduled generation idempotent.
- Amounts are VND integer minor-free values. Use an integer-safe type such as
  `long` (with validation/overflow handling); never use floating point for
  money, reconciliation, or balances.
- Apply matched payments to outstanding periods oldest-first (FIFO). Preserve
  exact allocation records, permit partial and overpayments as configured, and
  make replay/retry safe from duplicate allocation.
- A bank email/message and its resulting bank transaction must be idempotently
  ingested using a unique provider message identifier. Duplicate delivery,
  retry, and restart must never duplicate a transaction, notification, or
  payment allocation.
- Outgoing bank transactions are ledger records only; do not automatically
  create expenses from them.
- Every manual financial adjustment and expense create/update/delete requires
  an immutable audit record containing actor, timestamp, action, and safe
  before/after values. Support an intentional correction/undo path; do not
  mutate history invisibly.

## Security and authorization

- Enforce authorization on the server for every endpoint. Never trust a role,
  member ID, payment total, due-period ID, or visibility decision supplied by
  the client.
- Public endpoints may return only aggregate data, member names, and high-level
  status. The anonymous payer endpoints may additionally return only the
  selected member's currently payable periods, server-calculated total, QR,
  fund transfer-account details and standardized transfer content. They must never expose email, phone, full
  history, tokens, raw transaction content, audit logs, or reconciliation data.
- Anonymous payer lookup is not authentication: match exact normalized names,
  require member-code disambiguation for duplicate names, rate-limit and audit
  lookup/intent creation, and return generic errors without leaking data.
- Use least-privilege OAuth2 for Gmail; request read-only access and filter to
  the intended Sacombank sender and notification subject/label. Encrypt refresh
  tokens and sensitive account data at rest; use approved secret injection,
  never committed configuration or logs.
- Authentication and role separation: Super Admin owns system/mailbox
  configuration; Admin handles day-to-day members, expenses, and transaction
  review. Apply rate limiting to public and anonymous-payer endpoints, restrictive
  CORS, input validation, and generic external errors.
- Retain raw email payloads for the SRS-required period with access controls.
  Avoid putting raw payloads, personally identifiable information, bearer
  tokens, or account numbers in application logs, traces, test snapshots, or
  exception messages.

## Reconciliation and payment intent

- Creating a QR/payment flow creates a durable, expiring payment intent whose
  amount and due periods are determined server-side. New intents use exactly
  one immutable, globally unique 10-character `A-Z0-9` payment reference,
  stable for each member/month; a multi-month QR uses the earliest selected
  month. Historical intents retain legacy content for backward compatibility.
- Reconciliation must use the SRS multi-signal model: the opaque payment
  reference as an exact delimited token in parsed description, exact pending
  amount, rate multiple/owed periods, and payment-intent timing plus amount.
  Wallet/IBFT transfer content can be altered, so never rely on text matching
  alone.
- Thresholds are system configuration, not hard-coded: combined confidence
  >= 0.85 auto-records; 0.5–<0.85 becomes a ranked Admin suggestion; <0.5 is
  fully manual. Record confidence, signals, method (`auto`/`manual`), and the
  reviewer where applicable.
- A false auto-match is a financial integrity incident. Make every automatic
  match reviewable and correctable, and use transactions/optimistic concurrency
  or equivalent safeguards around concurrent review and allocation.
- Parser changes require regression fixtures for direct transfer and
  intermediary (MoMo/ZaloPay/IBFT) variants. Fixtures must be redacted or
  synthetic and tests must cover malformed/unrecognized messages.

## Architecture and persistence

- Keep Spring modules cohesive around member/dues, expenses, payments,
  reconciliation, email ingestion, notifications, authentication, audit, and
  configuration. Controllers should be thin; keep domain rules out of HTTP and
  persistence adapters.
- MongoDB is the system of record. Establish required unique/indexed fields
  from SRS section 6 before relying on them, and create indexes through a
  repeatable, observable migration/initialization mechanism.
- Compute fund balance from the authoritative ledger plus recorded expenses;
  do not add an unverified mutable balance cache. If snapshots are introduced,
  document their derivation and reconciliation process.
- Background work uses `@Scheduled` and the jobs collection unless a deliberate
  architecture decision introduces another queue. Jobs must have locking,
  retry/backoff, dead-letter/failure visibility, and idempotency.
- Use an interface boundary for notifications so email works now and other
  channels can be introduced later without coupling to financial logic.
- Publish a versioned OpenAPI/Swagger document. Maintain backward compatibility
  for the frontend or use an explicit version/deprecation plan. Generate or
  validate the spec as part of CI when the project is scaffolded.

## API and validation conventions

- Use clear resource-oriented endpoints and explicit request/response DTOs;
  do not expose MongoDB entities or internal reconciliation fields by default.
- Validate syntax and business constraints at the boundary, return stable error
  codes/messages, and distinguish authentication, authorization, validation,
  conflict, and not-found responses without leaking sensitive details.
- Make financial mutations and inbox/webhook-like ingestion endpoints
  idempotent. Use an idempotency key or a well-defined unique business key
  whenever a client/provider can retry.
- Paginate and filter admin lists server-side. Apply an allowlist for sort
  fields and bounded page sizes.
- Health/readiness endpoints must be suitable for Kubernetes probes and expose
  no secrets or sensitive connectivity details. Emit structured, redacted logs
  and metrics for API, scheduler, parser, matching, and notification failures.

## Change and verification workflow

1. Inspect `README.md`, existing code, local instructions, build files,
   `git status`, active configuration, and current test coverage. Preserve
   unrelated user changes.
2. Map the change to SRS requirement IDs, data invariants, API contract impact,
   authorization impact, migration/index impact, and rollback plan.
3. Implement the smallest safe change. Do not silently change contribution
   calculation, matching thresholds, retention, or authorization semantics.
4. Add focused unit tests for domain rules, integration tests for persistence
   and API authorization, and regression tests for parser/reconciliation work.
   Include retry/idempotency and concurrent-review cases for financial flows.
5. Run the repository's formatter, static analysis, unit/integration tests, and
   build. If container, MongoDB, or credentials are required but unavailable,
   report precisely which verification was not run and how to run it; never
   bypass TLS, authentication, or test failures.
6. For schema/index/configuration changes, document the forward migration,
   compatibility window, monitoring signal, and rollback/correction procedure.

## Operations boundaries

- Containerization, Helm/ArgoCD, MongoDB backups, and Kubernetes deployment are
  part of the intended architecture, but do not deploy, modify CI/CD, alter
  cloud/Kubernetes resources, rotate secrets, or run migration against a shared
  environment without explicit user approval.
- Before any environment mutation, identify account/project, cluster context,
  namespace, release, image tag, and database target; state blast radius and
  rollback. Prefer dry-runs, saved Terraform/OpenTofu plans, Helm diff/template,
  and declarative manifests.
- Daily backups and a tested restore runbook are acceptance requirements. Do
  not claim these operate unless evidence has been collected.
- Do not commit, push, publish artifacts, or open a pull request unless
  explicitly requested.
