# Backend MVP

The executable Phase 1 backend is a Java 21 / Spring Boot / MongoDB service.
The integration contract is [`openapi.yaml`](openapi.yaml); the SRS remains in
the repository root and is the business source of truth.

## Run locally

Use a local MongoDB and inject an Admin credential; neither credentials nor
real bank details are committed.

```bash
export MONGODB_URI='mongodb://localhost:27017/fund_management'
export APP_ADMIN_USERNAME='local-admin'
export APP_ADMIN_PASSWORD='use-a-long-local-password'
export FUND_BANK_NAME='Sacombank'
export FUND_BANK_CODE='970403'
export FUND_ACCOUNT_NUMBER='your-account-number'
export FUND_ACCOUNT_HOLDER='YOUR FUND NAME'
mvn spring-boot:run
```

For synthetic data only, add `SPRING_PROFILES_ACTIVE=dev`. It creates a single
`DEMO01` member with an `.invalid` email and no real financial data.

`/actuator/health` is suitable for a basic liveness/readiness probe. Swagger
UI is at `/swagger-ui.html`; the versioned hand-maintained frontend contract is
`docs/openapi.yaml`.

## Phase 1 coverage

- Public aggregate overview without individual amounts.
- Magic-link personal overview and server-calculated payment intents/VietQR URL.
- Admin member CRUD with soft deletion, regeneration of hashed 256-bit tokens,
  idempotent monthly dues generation, FIFO manual payment allocation, expenses,
  audit records, dashboard and six-month series.
- `personal-link-requests`, email ingestion/reconciliation review, and mutable
  settings are explicitly Phase 2. Their known endpoints return `202` generic
  acceptance or `501`, as described in OpenAPI.

## Data and operational notes

Mongo creates the member code, magic-token hash, due `(memberId, month)`, and
manual-payment idempotency indexes at startup. Back up Mongo before real data
imports. Money values are `long` integer VND only.

Manual allocation first writes a durable idempotency record so a retried key
returns the same payment. Production financial mutations should run against a
Mongo replica set with transactions enabled; standalone Mongo cannot provide
cross-document atomicity, so do not use it for shared production traffic.
