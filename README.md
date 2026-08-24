# fund-anagement-backend
# Software Requirements Specification (SRS)
## Group Fund / Treasurer Management System

| | |
|---|---|
| **Version** | v2.2 – opaque payment-reference update |
| **Date** | 2026-08-24 |
| **Author** | Long (DevOps Engineer) — drafted with Claude |
| **Status** | Implemented in source; production acceptance checks in progress |
| **Scope** | Internal web app to manage a single group fund (~40–60 members) |
| **Target stack** | Backend: Java (Spring Boot) · Database: MongoDB · Frontend: static SPA served via Nginx |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [System Overview](#2-system-overview)
3. [User Roles (Actors)](#3-user-roles-actors)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [Data Model (MongoDB)](#6-data-model-mongodb)
7. [Key Workflows](#7-key-workflows)
8. [Proposed Technical Architecture](#8-proposed-technical-architecture)
9. [UI/UX Design Guidance](#9-uiux-design-guidance)
10. [Risks & Technical Recommendations](#10-risks--technical-recommendations)
11. [Delivery Roadmap](#11-delivery-roadmap)
12. [Acceptance Criteria](#12-acceptance-criteria)

---

## 1. Introduction

### 1.1 Purpose

This document specifies requirements for the **Group Fund Management System** (the "System"), replacing the current manual Excel-based tracking of a group's monthly dues and expenses. It is the shared source of truth between the treasurer (product owner) and the development team(s), used to:

1. Align scope before implementation.
2. Drive architecture and UI design.
3. Serve as the acceptance baseline once delivered.

### 1.2 Scope

The System serves **exactly one fund/one group** in v1. Core capability groups:

- Member management, custom contribution rates, and join-month baseline logic.
- Monthly due tracking per member.
- VietQR generation and payment-content strategy for dues collection.
- Automatic ingestion of Sacombank balance-change emails, parsing, and reconciliation against member dues.
- Expense management (date, amount, purpose, category).
- Dashboards/statistics (income/expense, balance, compliance rate, top late payers).
- Email reminders for overdue members.

**Out of scope for v1.0**: multi-fund/multi-tenant support, integrated payment gateways (bank transfer via QR only), native mobile app (responsive web only).

### 1.3 Audience

Treasurer (product owner), backend developers, frontend developers, DevOps/infra, and group members who need to reference the fund rules.

### 1.4 Glossary

| Term | Meaning |
|---|---|
| Treasurer / Admin | System administrator: manages members, records expenses, reviews transactions |
| Member | Group participant with a recurring contribution obligation |
| Baseline month | The month from which a member's due obligation starts (usually the month after joining) |
| Due period | A specific month for which a member owes a specific amount |
| Transaction | A bank money movement (in/out), captured from email or entered manually |
| Reconciliation | The process of matching a bank transaction to the correct member + due period(s) |
| VietQR / Napas 247 | Vietnam's interbank quick-transfer QR standard |
| IBFT | Interbank Funds Transfer |

### 1.5 Source Data / Discovery Inputs

This SRS is grounded in real artifacts reviewed during discovery:

- The legacy "fund handover" Excel workbook (sheets for 2024/2025/2026): member roster, per-month contribution tracking (rates vary by person, some members start mid-year), an expense table (date – purpose – amount), and a summary block (opening balance / total income / total expense / current balance).
- A sample Sacombank balance-change notification email, showing that when funds pass through an intermediary wallet (MoMo) and then IBFT into the Sacombank account, **the original transfer content typed by the sender is overwritten** by the intermediary's own transaction reference. This is a critical technical constraint addressed in §6.3 and §10.
- Fund balance at time of handover: **44,763,092 VND** (per the workbook; the live figure will differ by the time of go-live).

---

## 2. System Overview

### 2.1 Current Problem

The fund is currently tracked in a shared Excel file, manually updated by the treasurer. Limitations:

- No real-time visibility into each member's contribution status.
- Reconciling bank transactions to payers is fully manual and error-prone, especially when transfer content is mangled by intermediary wallets/channels.
- No self-service lookup channel for members to check their own status.
- No automatic reminders for late payers.
- No automated charts/statistics — everything is built manually in Excel.

### 2.2 Proposed Solution

A client–server internal web app:

- **Backend (Java/Spring Boot)** exposing a REST API, owning all business logic (dues calculation, reconciliation engine, expense tracking).
- **MongoDB** as the primary data store (document model — see §6).
- A background **email-ingestion worker** (can run inside the backend service or as a separate deployable Java process) that connects to a mailbox, parses bank notifications, and feeds the reconciliation engine.
- **Frontend**: a static single-page app, build-time compiled, served as static assets via **Nginx** (which can also reverse-proxy `/api` to the backend). Framework choice (React/Vue/Svelte/plain) is left open — nothing here depends on SSR.
- Everything containerized (Docker), deployed to the treasurer's existing self-managed Kubernetes/VPS.

### 2.3 Assumptions & Constraints

- Single fund/single group in v1 (no multi-tenancy).
- Small scale: ~40–60 members, low transaction volume (tens per month) — simplicity and low operating cost matter more than raw performance.
- Exactly one source bank account (Sacombank) monitored in v1.
- Mailbox access uses OAuth2 (no plaintext email passwords stored).
- Historical 2024–2026 data from Excel will be migrated once during onboarding.
- Business rule preserved from the legacy sheet: each member has a **customizable** contribution amount (default 100,000 VND/month, override-able per member) and a **join-month baseline** for when dues start.
- **Two separate repositories**: `frontend` and `backend`. This SRS is the single shared source of truth and is **not** split between repos (see §8.4).

---

## 3. User Roles (Actors)

| Role | Description | Key Permissions |
|---|---|---|
| Treasurer (Admin) | 1–2 people who run the fund | Authenticated access only. Manage members, record expenses, review/reconcile transactions, configure contribution rates, view all reports, system configuration |
| Member / Payer | Group participant with a due obligation | View the public overview; without login, select their own exact name and generate a payment QR for outstanding dues |
| Public / Guest | Anyone with the public link | View the public payment dashboard, including member/month dues and high-level fund metrics; never receive contact data, OAuth material, credentials, or raw bank messages |
| Automated system (Email Worker) | Background process | Reads bank emails, parses, creates transaction records, auto-reconciles unique annual references, or quarantines unmatched messages |

> **Approved product policy**: the public payment dashboard intentionally shows member codes, due periods, amount due, amount paid, outstanding amount and status for all active members. It still never returns email, phone, OAuth material, credentials, raw bank messages, audit logs or reconciliation internals. Public access is not authentication and payment intent creation remains server-priced.

---

## 4. Functional Requirements

### 4.0 Master contribution plan and annual payment reference (2026 update)

At onboarding or member creation, Admin imports a master plan containing the
contribution start month, the number of contribution months in the plan year,
and any months already paid in the source workbook. The year-generation
operation is idempotent and marks those imported months paid without creating a
bank reconciliation record. New QR/payment intents use one opaque random
10-character reference per member/year; legacy member/month references remain
readable for historical transactions. A payment plan snapshots its selected
due-period IDs and server-calculated total. Selection can be explicit, all
outstanding periods, a month count, or all periods through a selected month.
Bank alerts containing a unique annual reference are automatically allocated
oldest-first within that year; underpayments remain partial and excess is
retained as an unallocated member credit on the payment ledger. No Admin review
is required for a unique, configured annual reference. Unmatched or ambiguous
messages are quarantined without changing dues; they are never silently assigned.
Each QR plan is scoped to one calendar year; a cross-year prepayment is split into
one QR per year so the reference and allocation remain unambiguous.

The canonical annual plan has fourteen ordered periods: `YYYY-01` through
`YYYY-12` are calendar months and `YYYY-13`/`YYYY-14` are mandatory bonus
periods. Bonus keys are period identifiers, not `YearMonth` values. Admins can
manage this master plan at `GET /api/v1/admin/payment-plans?year=YYYY` and
`PUT /api/v1/admin/members/{memberId}/payment-plan`; saving is idempotent,
audited with before/after provenance, materializes due periods, and seeds
selected paid periods without creating ledger income or manual payments.
Existing ledger-paid periods cannot be silently downgraded.

Requirements are grouped into 8 modules (A–H). Format: `FR-[Module]-[No.]`.

### 4.1 Module A — Member Management

| ID | Requirement | Note |
|---|---|---|
| FR-A-01 | Authenticated Admin can create/edit/deactivate members: full name, member code, email, phone (optional), join month/year. | Soft-delete only, to preserve contribution history |
| FR-A-02 | System auto-computes the "baseline month" = the month immediately after the join month (Admin can override for special cases). | Matches legacy Excel logic |
| FR-A-03 | Each member has a default contribution rate of 100,000 VND/month; Admin can set a custom rate per member, effective from a given month, with a full rate-change history. | Covers real cases such as 1,000,000 VND and 200,000 VND/month members |
| FR-A-04 | Admin can mark a member "on leave" (no dues accrue during the leave window) or "left the group" (dues stop from the departure date). | Case: a member marked "cannot join" mid-year |
| FR-A-05 | System maintains a normalized full name for anonymous payer lookup. The payer enters/selects the exact name; if names collide, the UI requires the member code to disambiguate. | This is convenience lookup, not authentication |
| FR-A-06 | Bulk import of members and historical contribution data from the legacy Excel file (one-time, at rollout). | Migration script, not a recurring feature |

### 4.2 Module B — Dues / Contribution Tracking

| ID | Requirement | Note |
|---|---|---|
| FR-B-01 | At the start of each month, the system auto-generates one "due period" per active member, using that member's current contribution rate; each annual plan has 12 regular periods plus mandatory bonus periods 13 and 14. | Scheduled job |
| FR-B-02 | Each due period has a status: Unpaid / Fully Paid / Partially Paid / Overpaid (overpayment optionally rolls forward, configurable). | |
| FR-B-03 | When a transaction is reconciled to a member, the system applies it against outstanding due periods oldest-first (FIFO). | Handles lump-sum payments covering several months at once |
| FR-B-04 | Authenticated Admin can view each member's total paid since joining, remaining balance owed, and a month-by-month breakdown. | |
| FR-B-05 | The public payment dashboard shows each active member's month-by-month due details and status. | Contact data and bank internals remain private |
| FR-B-06 | Admin can manually adjust a due period (e.g., waive a fee, add a note); every manual adjustment is logged with actor and timestamp. | Audit trail |

### 4.3 Module C — Expense Management

| ID | Requirement | Note |
|---|---|---|
| FR-C-01 | Admin records an expense: date, amount, purpose, category (optional: food, travel, gifts, other), requester/notes, optional receipt attachment. | |
| FR-C-02 | Expense list is filterable by date range and category; exportable to Excel/PDF. | |
| FR-C-03 | Admin can edit/delete a mis-entered expense; changes are logged. | Audit trail |

### 4.4 Module D — Payment Link & QR Generation

| ID | Requirement | Note |
|---|---|---|
| FR-D-01 | Without login, a payer selects a member from the public payment dashboard, selects due period(s) to pay (default: all outstanding), and the server computes the total. | Member code is shown for unambiguous selection |
| FR-D-02 | The system generates a **VietQR (Napas 247)** code containing: fund account number, amount, and exactly one opaque 10-character `A-Z0-9` transfer reference (see §6.3). | The reference must not encode member code or month |
| FR-D-03 | The QR page also shows a "Copy content / account number" button for manual transfers. | |
| FR-D-04 | Creating a QR marks the related due period(s) as "Awaiting payment" and records a **payment intent** for later matching. | |
| FR-D-05 | The payment dashboard endpoint returns member/month payment data under the approved public policy; payment intent creation returns only the selected periods, total, QR and opaque transfer reference. | Never return contact data, OAuth material, credentials or raw bank messages; rate-limit and audit intent creation |

### 4.5 Module E — Email Ingestion & Auto-Reconciliation

| ID | Requirement | Note |
|---|---|---|
| FR-E-01 | System connects to a mailbox (Gmail API, OAuth2) and receives Sacombank balance-change notifications near-real-time (five-second polling during the five-minute QR detection window) plus a full, paginated mailbox reconciliation every 30 minutes. | The batch scans matching account content regardless of read/unread state; repeated provider IDs are idempotent |
| FR-E-02 | A parser extracts: account number, transaction datetime, amount (+/-), transfer content, and available balance from the Sacombank HTML email body. | Needs a regression test suite covering multiple content variants (direct transfer, QR, MoMo/ZaloPay relay, etc.) |
| FR-E-03 | For incoming (+) transactions, the reconciliation engine (see §6.3) scores candidate matches against member + due period. | |
| FR-E-04 | If matched with high confidence (configurable threshold), the system auto-records the payment, updates the due period, and emails a confirmation to the member. | Fulfils the "fully automatic" choice made by the treasurer |
| FR-E-05 | If the annual reference is missing, invalid or ambiguous, the transaction is quarantined without changing any member balance; valid unique annual references never require Admin review. | |
| FR-E-06 | Outgoing (-) transactions are logged in the bank-transaction ledger and manually cross-checked against the Expense table (not auto-created as expenses, to avoid mislabeling purpose). | |
| FR-E-07 | Raw email payloads are retained for at least 12 months for dispute resolution. | |
| FR-E-08 | Retry and alerting mechanisms when the mailbox connection drops or parsing fails repeatedly. | Observability — see §5, §8 |

### 4.6 Module F — Statistics & Reporting (Dashboard)

| ID | Requirement | Note |
|---|---|---|
| FR-F-01 | Overview dashboard: current fund balance, monthly income/expense chart, % of members fully paid for the current month. | Public page |
| FR-F-02 | Yearly income/expense chart, comparable across years (2024/2025/2026...). | |
| FR-F-03 | Expense breakdown by category (table/chart). | |
| FR-F-04 | List/chart of top late-paying members (Admin only). | Sensitive |
| FR-F-05 | Export monthly/quarterly/yearly summary reports to Excel or PDF. | |
| FR-F-06 | Audit log of all financial data changes (who, when, what) — Admin only. | |

### 4.7 Module G — Notifications & Reminders

| ID | Requirement | Note |
|---|---|---|
| FR-G-01 | Auto-send a confirmation email to a member as soon as their payment is reconciled/recorded. | |
| FR-G-02 | Send periodic reminder emails (e.g. day 5 and day 20 of each month, configurable) to members with outstanding dues, including a payment QR embedded directly in the email. | Channel: Email, per treasurer's choice |
| FR-G-03 | Internal alert email to Admin when: the email worker fails repeatedly, an unusually large transaction appears, or the manual-review queue exceeds a threshold. | |
| FR-G-04 | Design the Notification module behind a provider interface so Telegram/Zalo can be added later without touching core business logic. | Future-proofing only, not needed now |

### 4.8 Module H — Administration & Authentication

| ID | Requirement | Note |
|---|---|---|
| FR-H-01 | Admin logs in via username/password or Google OAuth, with optional 2FA (recommended, not mandatory for MVP). | |
| FR-H-02 | At least two permission levels: Super Admin (full control, system config, mailbox connection) and Admin (day-to-day: expenses, transaction review). | Anticipates a second treasurer |
| FR-H-03 | System configuration (monitored bank account, default contribution rate, auto-reconciliation threshold, reminder schedule) is managed via the Admin UI, not hard-coded. | |
| FR-H-04 | All create, edit, deactivate, expense, reconciliation, report and configuration operations require authenticated Admin authorization. Anonymous access includes the public payment dashboard and QR creation, but no mutation other than creating a durable payment intent. | Enforce on API, not only in the UI |

---

## 5. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Security | No plaintext mailbox passwords (OAuth2 tokens, encrypted at rest). Encrypt sensitive fields (account numbers, email, phone) at the application or database layer. HTTPS everywhere. CORS restricted; rate-limit anonymous payer lookup/QR APIs; enforce Admin authorization server-side. |
| Performance | Public pages load in < 2s with ≤100 members & a few hundred transactions/year. No need to optimize for high load in v1.0. |
| Availability | Target ~99% uptime (periodic maintenance accepted), consistent with a small self-hosted deployment. Expose health-check endpoints for Kubernetes liveness/readiness probes. |
| Scalability | Modular design (member/dues, expense, email-ingest, notification) to allow a second fund or a second bank channel later. |
| Observability | Structured JSON logs, exportable via OpenTelemetry; compatible with Prometheus/Grafana and ELK/Kibana on existing infra. Alert on repeated email-worker failures. |
| Backup & Recovery | Daily MongoDB backup (`mongodump` or volume snapshot), retention ≥30 days. Documented restore runbook. |
| Auditability | Every create/update/delete on financial data is audit-logged (actor, timestamp, before/after values). |
| Data Portability | Full data export (members, transactions, expenses) to CSV/Excel at any time — avoid vendor/data lock-in. |
| Maintainability | Containerized (Docker), Infrastructure as Code (Helm chart/Ansible), CI/CD pipeline (build → test → deploy), matching the treasurer's existing tooling (Jenkins/GitLab CI, ArgoCD). |
| Device Compatibility | Responsive UI, mobile-first, since most members will access via phone. |

---

## 6. Data Model (MongoDB)

MongoDB is document-oriented; the model below favors **embedding** for data always read together (e.g. rate history inside a member document) and **separate collections** for data queried independently at scale (due periods, transactions).

### 6.1 Collections

**`members`**
```json
{
  "_id": "ObjectId",
  "member_code": "string (unique, indexed)",
  "full_name": "string",
  "email": "string",
  "phone": "string | null",
  "join_month": "YYYY-MM",
  "baseline_month": "YYYY-MM",
  "status": "active | paused | left",
  "normalized_full_name": "string (indexed; used only for exact anonymous payer lookup)",
  "contribution_rates": [
    { "amount": 100000, "effective_from": "YYYY-MM", "effective_to": "YYYY-MM | null" }
  ],
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

**`due_periods`** (one document per member per month)
```json
{
  "_id": "ObjectId",
  "member_id": "ObjectId (indexed)",
  "month": "YYYY-MM",
  "amount_due": 100000,
  "amount_paid": 0,
  "status": "unpaid | partial | paid | overpaid",
  "payments": [
    { "transaction_id": "ObjectId", "amount": 100000, "matched_at": "ISODate", "method": "auto | manual" }
  ],
  "notes": "string | null"
}
```
Unique compound index: `(member_id, month)`.

**`bank_transactions`**
```json
{
  "_id": "ObjectId",
  "occurred_at": "ISODate (indexed)",
  "account_number": "string",
  "amount": 100000,
  "direction": "in | out",
  "raw_content": "string",
  "parsed_content": "string | null",
  "available_balance": 9562071,
  "email_message_id": "string (unique, indexed)",
  "reconciliation_status": "matched | needs_review | ignored (indexed)",
  "match_candidates": [
    { "due_period_id": "ObjectId", "confidence": 0.92, "signals": ["content_code", "amount_match"] }
  ],
  "matched_due_period_ids": ["ObjectId"],
  "reviewed_by": "ObjectId | null",
  "reviewed_at": "ISODate | null"
}
```

**`payment_intents`** (created when a member generates a QR — used as reconciliation Signal 4, see §6.3)
```json
{
  "_id": "ObjectId",
  "member_id": "ObjectId (indexed)",
  "due_period_ids": ["ObjectId"],
  "total_amount": 300000,
  "transfer_content": "string (exactly 10 uppercase A-Z0-9 characters for new intents)",
  "created_at": "ISODate (indexed)",
  "expires_at": "ISODate",
  "status": "pending | fulfilled | expired"
}
```

**`payment_references`** (stable opaque code per member and calendar year)
```json
{
  "_id": "ObjectId",
  "member_id": "ObjectId",
  "year": 2026,
  "code": "string (unique, exactly 10 A-Z0-9 characters)",
  "created_at": "ISODate"
}
```
Unique indexes: `(member_id, year)` and `code`. New payment plans reuse the
same annual code, including plans that pay several months in advance. Legacy
month-scoped records remain readable and existing intents are not rewritten.

**`expenses`**
```json
{
  "_id": "ObjectId",
  "date": "ISODate",
  "amount": 920000,
  "purpose": "string",
  "category": "string | null",
  "requested_by": "string | null",
  "attachment_url": "string | null",
  "created_by": "ObjectId",
  "created_at": "ISODate",
  "updated_at": "ISODate"
}
```

**`users`** (Admin accounts)
```json
{ "_id": "ObjectId", "email": "string", "password_hash": "string | null", "oauth_id": "string | null", "role": "super_admin | admin" }
```

**`audit_logs`**
```json
{ "_id": "ObjectId", "actor": "ObjectId", "action": "string", "entity_type": "string", "entity_id": "ObjectId", "before": {}, "after": {}, "timestamp": "ISODate (indexed)" }
```

**`notification_logs`**
```json
{ "_id": "ObjectId", "member_id": "ObjectId", "type": "reminder | confirmation | alert", "channel": "email", "status": "sent | failed", "sent_at": "ISODate" }
```

> Fund balance over time (for charts) is **computed on the fly** via an aggregation pipeline over `bank_transactions` + `expenses`, rather than stored redundantly. If this becomes a performance concern at scale, add a `fund_snapshots` collection populated by a nightly job.

### 6.2 Key Relationships

- `members` 1—N `due_periods` (referenced by `member_id`).
- `due_periods` N—N `bank_transactions` via the `payments` array / `matched_due_period_ids` (one transaction can cover several months; one month can be covered by several partial transactions).
- `payment_references` maps each member/year to one immutable opaque transfer code; `payment_intents` stores a plan snapshot and links the checkout action to its due periods.
- `expenses` is independent of members/due periods.

### 6.3 Auto-Reconciliation Logic

This is the highest technical risk area, based on the sample email: when a member pays via an intermediary wallet (MoMo, ZaloPay...) before it lands in the Sacombank account via IBFT, **the original transfer content typed by the sender is typically overwritten** by the intermediary's own transaction reference (e.g. `"...MOMO143339946997MOMO CKN 594950 d1Y67G9Y - MBBANK IBFT..."`). Content string matching alone is therefore not reliable enough. Recommended approach: **multi-signal matching**, ranked by confidence:

1. **Signal 1 — Content contains a configured annual payment reference token**: the parsed transfer description must contain the stored opaque 10-character member/year reference as a delimited A-Z0-9 token (surrounding bank references are allowed); this is the strongest content signal and does not reveal member identity.
2. **Signal 2 — Exact amount match against a single pending due period** for one member: confidence 0.6–0.85. Especially useful because contribution rates differ across members (100k / 200k / 1,000,000), which narrows candidates.
3. **Signal 3 — Amount is an exact multiple of a member's rate** (e.g. 300,000 = 3 × 100,000) and that member owes exactly that many months: confidence 0.5–0.7.
4. **Signal 4 — Timing proximity to a `payment_intent`**: the transaction occurs shortly after (e.g. within 30 minutes) a member generated a QR for a specific amount, and the amount matches: confidence ≥ 0.85. This is the **strongest signal** because it does not depend on transfer content being preserved by a third party.

Suggested thresholds (configurable, not hard-coded): combined confidence **≥ 0.85 → auto-record** (FR-E-04); **0.5–0.85 → suggested match**, one-click confirm by Admin; **< 0.5 → "source unclear"**, fully manual.

**Transfer-content contract when generating a QR:**

- Prefer VietQR/Napas 247 codes tied directly to the Sacombank account — when a member transfers directly from another bank's app (not through a wallet), content is generally preserved through the Napas 247 rail.
- The content is exactly one random uppercase code matching `[A-Z0-9]{10}`. It is generated once per member/year, unique globally, immutable, and reused by all intents for that year. A QR spanning multiple months uses the annual code for the plan year.
- New code generation is collision-retried under unique Mongo indexes. Historical due periods are assigned lazily when a QR is created; historical payment intents are preserved unchanged for backward compatibility.
- Auto-reconciliation requires the stored 10-character annual reference as an exact delimited token and a configured receiving account. It does not depend on unread state or a still-pending QR intent. Altered/ambiguous content is quarantined without allocation.
- Transactions that did not originate from the system's QR flow are accepted when they contain a unique annual reference; otherwise they remain unmatched and do not alter balances.

---

## 7. Key Workflows

### 7.1 Monthly Dues Payment

1. Payer opens the public payment dashboard and selects a member. No login is required.
2. Payer selects a payment plan (all outstanding, N months, or through a month) → system creates one server-priced `payment_intent` snapshot → generates a VietQR code with the member/year opaque 10-character reference. Completed and outstanding periods are shown with their amounts and statuses.
3. Payer scans the QR with any banking/wallet app → transfers.
4. Sacombank sends a balance-change email → Email Worker receives, parses it.
5. Reconciliation engine checks the exact annual reference token and configured receiving account → allocates FIFO in that calendar year, including late or prepayment emails, without unread dependence.
6. System updates the due period(s) → sends a confirmation email → updates the dashboard.

### 7.2 When Auto-Reconciliation Fails

1. The transaction is kept in the immutable bank-transaction ledger with a quarantine status.
2. No due period or balance is changed until a unique annual reference can be established by a later full mailbox scan or a corrected source message.

### 7.3 New Member Joining Mid-Year

1. Admin adds the new member with their join month (e.g. June).
2. System sets `baseline_month = July` (the following month) — no due periods are created for prior months.
3. From July onward, due periods are generated automatically on the same monthly schedule as everyone else.

### 7.4 Expense Recording

1. Admin records an expense (date, amount, purpose, category).
2. It appears immediately on the dashboard, reducing the displayed fund balance (`balance = opening balance + reconciled income − recorded expenses`).
3. Periodic manual cross-check between outgoing `bank_transactions` and the `expenses` table to ensure they match.

---

## 8. Proposed Technical Architecture

### 8.1 Components

| Component | Proposed Technology | Role |
|---|---|---|
| Frontend | Static SPA (framework TBD — React/Vue/etc.), built at CI time, served as static files by **Nginx** | Browser calls only same-origin `/api/*`; Nginx must reverse-proxy it to `fund-management-backend.fund-management.svc.cluster.local:8080`, never to a public backend URL |
| Backend API | **Java, Spring Boot** | REST API, business logic (dues, reconciliation, expenses), auth |
| Database | **MongoDB** (replica set recommended even at small scale, for durability; single node + backups is an acceptable v1 trade-off) | Primary data store |
| Email Worker | Java (Spring Boot module or a separate deployable jar), using the **Gmail API Java client** (OAuth2) + Google Pub/Sub push, with polling as fallback | Fetches, parses, and feeds transactions into the reconciliation engine |
| Background jobs | Spring `@Scheduled` + a lightweight `jobs` collection in MongoDB as a queue for retries (avoids adding Redis/RabbitMQ at this scale); revisit if volume grows | Async parse → match → notify pipeline |
| Reconciliation Engine | A dedicated Java module/package inside the backend | Multi-signal scoring (§6.3) |
| QR Generation | Build the EMVCo/VietQR payload directly in Java, or call a public VietQR image API (e.g. img.vietqr.io) | Generates Napas 247-compliant QR codes |
| Notifications | SMTP (SendGrid/Amazon SES, or an internal SMTP relay) | Confirmation/reminder/alert emails |
| Observability | Prometheus + Grafana (metrics), ELK/Kibana or an OpenTelemetry Collector (logs/traces) | Monitor the email worker, API, and alerting |
| Deployment | Docker images, Helm chart, ArgoCD (GitOps), on the treasurer's self-managed VPS/Kubernetes | Matches the chosen self-hosted model |
| CI/CD | GitHub Actions/Jenkins/GitLab CI → build & test → push to registry → ArgoCD sync | Reuses existing familiar pipelines |

### 8.2 Mailbox Connection Security

- Use OAuth2 (Gmail API) with the minimum necessary scope (read-only, ideally scoped to a specific label), never requesting send/delete permissions.
- Store refresh tokens encrypted (KMS/Secret Manager, or a Kubernetes `Secret` + sealed-secrets).
- Do not process the entire mailbox — filter strictly by sender (`info@sacombank.com.vn`) and by the balance-change notification subject pattern.

### 8.3 Infrastructure Deployment Strategy

- A dedicated Kubernetes namespace for the fund system; separate dev/staging/prod if feasible.
- Daily MongoDB backup CronJob, shipped to S3-compatible/MinIO object storage.
- `/healthz` endpoint per service for liveness/readiness probes.
- Production SPA requests **must** use the relative same-origin `/api/v1` base path. The browser must never receive a Kubernetes `*.svc` address or a direct/public backend host; only the in-cluster Nginx proxy resolves the backend Service FQDN.
- Nginx must use bounded upstream connect/read timeouts and return a gateway failure promptly when no backend endpoint is ready. The UI must distinguish that unavailable state from a valid "member not found" result.
- The backend may schedule on every eligible node. DNS/TLS egress to the managed MongoDB cluster is an operational prerequisite verified before rollouts; use a temporary Helm node selector only during a diagnosed infrastructure incident.
- Centralized logging via Fluentd/OTel Collector → Elasticsearch/Kibana (reusing prior experience with the Jenkins/GitLab logging pipeline).

### 8.4 Repository & Documentation Strategy (Two Repos)

Given the decision to split into `frontend` and `backend` repositories:

- **This SRS remains a single document**, owned centrally (e.g. a `docs` repo, or living inside the `backend` repo since it owns the business logic). It is **not duplicated or split per repo** — splitting a business/system-level spec across repos leads to drift (e.g. the dues calculation rule or reconciliation thresholds getting out of sync between two copies).
- Each repo instead keeps a **thin, repo-specific technical doc** (a README plus a few ADRs — Architecture Decision Records) that only references the relevant SRS sections rather than repeating them:
  - `backend` repo: technical design notes referencing §4 (Functional Requirements), §6 (Data Model), §8 (Architecture); the backend also **owns and publishes the OpenAPI/Swagger contract**.
  - `frontend` repo: UI implementation notes referencing §9 (UI/UX Guidance); consumes the backend's published OpenAPI spec as its integration contract.
- The **OpenAPI/Swagger spec** is the real artifact that should be versioned and treated as the contract between the two repos (e.g. published as a build artifact, or in a small shared `api-contract` repo/package that both sides pull from). This is what actually needs to be "split out" — not the SRS itself.
- Recommended lightweight process: any change to business rules or data model first updates this SRS (PR-reviewed), then propagates to the affected repo(s) and, if the API shape changes, to the OpenAPI contract.

---

## 9. UI/UX Design Guidance

This section directs detailed UI design work (wireframes/Figma); it is not a final design.

### 9.1 General Principles

- **Mobile-first**: most payers will open the payment page on a phone.
- **Clear and minimal**: present financial figures as simple tables and charts (bar/line); avoid decorative clutter.
- **Public payment dashboard**: Member/month due details are intentionally visible under the approved product policy; credentials, contact data and bank internals remain private.
- **Consistent status colors**: Green = fully paid, Yellow = one period missing, Red = two or more periods missing / long overdue.

### 9.2 Public Overview Dashboard

- Top block: current fund balance (large, prominent), plus current-month total income/expense.
- Bar chart of income vs. expense for the last 6–12 months.
- Member list: name + member code + status and a link to the payment dashboard.

### 9.3 Public Payment Dashboard

- Responsive two-column layout: member search/selection and due-period checklist on the left, QR panel on the right.
- Show month, amount due, amount paid, outstanding amount and status for the selected member; default to all outstanding periods.
- Include server-calculated total, bank details, opaque transfer content, QR download/share, and 5-second status polling for five minutes.
- Display a notice that public access is not authentication and that the dashboard never exposes contact data or raw bank messages.

### 9.4 Payment QR Page

- Large QR code, centered.
- Below: amount to transfer (large text), account number + account holder name, transfer content with a copy button.
- Real-time status pill: "Awaiting payment..." (yellow, small loading indicator) transitioning to "Payment confirmed ✅" once reconciled (lightweight polling or WebSocket).
- Small helper note suggesting a direct bank-app transfer rather than via a wallet, for faster processing (per §6.3).

### 9.5 Admin Area

- Sidebar navigation: Overview, Members, Expenses, Reconciliation, Statistics, Settings.
- Overview dashboard adds private widgets: Top late payers, "Transactions needing review" (prominent red count badge), yearly income/expense chart.
- Reconciliation page: table of unmatched transactions, each row with a ranked suggestion dropdown (member + month) and a one-click confirm button.
- Members page: management table with add/edit forms and rate-change history.
- Expenses page: quick-entry form + filterable/exportable list.
- Statistics page: yearly charts, expense category breakdown, report export.
- Settings page: monitored bank account, default contribution rate, reconciliation thresholds, reminder schedule, mailbox connection status (OAuth) with a reconnect button.

---

## 10. Risks & Technical Recommendations

| Risk | Impact | Mitigation |
|---|---|---|
| Transfer content overwritten by wallets/IBFT relays | Transactions fail to auto-match | Multi-signal reconciliation (§6.3); prioritize the `payment_intent` timing signal + amount over content alone |
| Sacombank email format changes (layout, HTML structure) | Parser breaks, transactions missed | Snapshot tests for the parser; alert on a spike in parse failures; retain raw emails to allow re-parsing after a fix |
| Public dashboard exposes financial information | Privacy impact for members | This is an explicit product decision; keep contact data, credentials, OAuth material, raw bank messages and reconciliation internals private, with rate limits and audit logs |
| Loss of OAuth mailbox access (expired/revoked token) | Automatic detection stops working | Early alerting on refresh-token failures; a fast re-auth procedure; always keep manual entry as a fallback |
| Incorrect auto-reconciliation (wrong member matched) | Due-balance errors, trust impact | High auto-match threshold (≥0.85); every auto-recorded match remains visible in history labeled "automatic" so Admin can audit/undo |
| Payment-reference migration or code collision | QR content changes or references become ambiguous | New `(member_id, year)` and global `code` unique indexes; collision retry; historical intents remain untouched. Rollback is code-only: disable new QR creation, preserve `payment_references`, and restore the previous application image; do not delete or rewrite financial records. |
| Single-person dependency for self-hosted infra | Operational risk if the treasurer is unavailable | Document deployment/restore runbooks; grant a second Admin |

---

## 11. Delivery Roadmap

### 11.1 MVP (Phase 1)

- Import historical data from Excel (members, rates, baseline, fund balance).
- Modules A & B (member management, dues) — no auto-reconciliation yet; Admin records payments manually.
- Module C (expenses) fully implemented.
- Module D: static VietQR generation (no auto-matching yet).
- Module F basics: overview dashboard, income/expense charts.
- Public payment dashboard + QR flow (no member login), with authenticated Admin member management.
- Basic Docker/K8s deployment with DB backups.

### 11.1.1 Current Implementation & Remaining MVP Work

Implemented in source: legacy roster/opening-balance import; authenticated Admin member create/edit/deactivate with audited 14-period master payment-plan API; public year-by-year 14-period contribution board; server-priced VietQR payment plans with stable opaque 10-character member-year references; shareable payment-intent details and status polling; same-origin frontend API routing through the backend Kubernetes Service; member due detail/adjustment and FIFO automatic payment allocation; expense CRUD/filter/CSV export; statistics/category reporting; audit-log UI/API; Super Admin settings; versioned OpenAPI v1.1 contract; idempotent monthly/year-plan generation; read-only Gmail OAuth/polling with distributed lease, receiving-account gating, parser and annual-reference auto-match independent of unread state; unmatched-message quarantine; notification-provider boundary with safe disabled defaults; Helm delivery with two backend replicas, anti-affinity, PDB and a daily 30-day MongoDB backup CronJob/restore runbook.

Operational checks that remain intentionally gated:

- Historical workbook ledger is retained with provenance; the one-time master import provisions only the configured start month, plan length and initially paid months. It does not require recurring Admin reconciliation.
- Let CI publish the images and ArgoCD roll the chart, then verify two ready backend Pods on eligible nodes and a successful backup Job/restore drill.
- Provision Gmail OAuth read-only credentials and the SMTP Secret. The parser, idempotent ledger, five-minute fast polling, and 30-minute full mailbox reconciliation flow exist; email delivery remains disabled rather than using invented credentials.
- Verify a generated VietQR with two banking apps and add browser-level E2E coverage against the deployed public payer and Admin workflows.

### 11.2 Phase 2 — Reconciliation Automation

- The safe foundations are implemented: parser interface/tests, idempotent bank-transaction ledger, quarantine for unmatched messages, stable opaque annual references, automatic FIFO allocation and notification provider boundary.
- Gmail API OAuth read-only adapter, Sacombank production-format regression corpus, multi-signal auto-match scoring, recurring reminders, and production alerting require the external mailbox/SMTP credentials and sample messages listed above.

### 11.3 Phase 3 — Enhancements

- Multi-admin permissions, full audit log.
- Advanced PDF/Excel reporting, multi-year comparisons.
- Additional notification channels (Telegram/Zalo) if needed.
- Consider multi-fund/multi-group support if the need arises.

---

## 12. Acceptance Criteria

1. After historical import, the fund balance shown in the system matches the handover balance in the Excel file (44,763,092 VND at the time of discovery, or the latest balance at actual handover).
2. For every member, the total amount paid in the system matches the legacy Excel total (zero discrepancy) after import.
3. A member joining mid-month is automatically assigned the correct baseline month.
4. Generated VietQR codes are valid and scannable by at least two different major banking apps in testing.
4a. Every newly generated payment intent uses exactly one immutable 10-character `A-Z0-9` reference; references are stable for member/year, globally unique, and historical intents remain readable without rewrite.
5. *(Phase 2)* Against a test set of ≥20 real sample emails (including MoMo/IBFT relay cases), the auto-reconciliation engine achieves ≥80% correct auto-match rate at the high-confidence threshold; all remaining cases appear fully in the manual-review queue with no transaction silently dropped.
6. An unauthenticated visitor can select any active member from the public payment dashboard, inspect member/month due details, and create a valid QR payment intent; public responses never expose contact details, credentials, OAuth material, raw bank messages or reconciliation internals.
7. Only an authenticated Admin can create, edit or deactivate members, and every such change is audit-logged.
8. The system deploys successfully via the CI/CD pipeline to the Kubernetes/VPS environment, with working DB backups and a restore runbook tested at least once.
