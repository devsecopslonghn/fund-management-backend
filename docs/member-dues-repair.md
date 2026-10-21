# Single-member due and credit repair

Use this procedure for the verified ANPH44225 incident only unless another
member's discrepancy is separately established. A missing due row alone does
not prove a payment discrepancy. Do not run global month/year generation or
mark bank-paid months as imported/seeded payments to repair credit.

## Preconditions and expected changes

- Inspect cluster context, namespace, running backend image and MongoDB database.
- Obtain authorization for the specific financial repair. Deploying the backend,
  pushing or merging source remains a separate action.
- Use MongoDB replica-set transactions and the existing unique member/month and
  payment idempotency indexes. The tool does not create or change indexes.
- Before applying, save the relevant due/payment documents and bank allocation
  links in a restricted snapshot. Never export raw bank messages or credentials.

For the incident observed on 2026-10-21: member starts in 2026-08 at 100,000 VND;
one existing 300,000 VND payment has 200,000 allocated to September/October and
100,000 credit. The annual materializer should create August, November,
December and bonus periods 13/14. Only August receives the 100,000 credit.
September/October retain their original allocations. The resulting plan has
three paid periods and four unpaid future/bonus periods, not seven paid periods.
Ledger income, expenses and fund balance must not change.

## Preview and apply

After deploying the API, a Super Admin can POST to
`/api/v1/admin/migrations/member-dues/repair`:

```json
{"memberCode":"ANPH44225","year":2026,"dryRun":true,"reason":"Repair verified missing August period"}
```

Check `createdPeriods`, each period's before/after paid amount, unchanged
`totalReceivedBefore/After`, credit 100,000 -> 0, and one bank link update.
Preview is read-only and does not write audit events. Apply the same request
with `dryRun:false` and the returned `previewToken`. A missing/stale token or
inconsistent ledger returns 409 without committing a repair. A new preview
after success should show no changes; applying that preview is a no-op.

An offline command runs the identical repair service without starting a web
server, scheduler, ingestion, or deploying an application. Use the locally
tested executable JAR and existing approved runtime MongoDB environment:

```sh
java -Dotel.javaagent.enabled=false \
  -Dloader.main=com.fundmanagement.api.ops.MemberDuesRepairCommand \
  -cp /path/to/tested-backend.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher < preview-request.json
```

Offline output contains `repair` (the API response), `ledgerBefore` and
`ledgerAfter` (income, expense, balance, payment/bank counts and a digest of
other members' documents). Compare these ledger objects before accepting the
repair as verified.

It requires `MONGODB_URI`, `MONGODB_DATABASE` and `FUND_REPAIR_ACTOR`; never print
their credential values. To apply, supply the preview token in stdin and pass
one new snapshot file path as the command's final argument. The command refuses
to overwrite snapshots and creates the snapshot with owner-only permissions.
This command is a privileged maintenance operation, not an alternative public
authentication path.

## Verification and correction

Compare payment count, total income, expenses and balance before/after. Verify
August/September/October are paid 100,000 each; existing payment amount stays
300,000; credit becomes zero; bank links reference all three due IDs. Check the
`MEMBER_DUES_REPAIRED` audit event with actor, reason and preview fingerprint.
Confirm other members' due/payment documents are unchanged.

Failures inside a transaction roll back automatically. If a commit result is
uncertain, re-read the ledger and obtain a new preview; do not replay writes
blindly. After a successful commit, correction requires a separately authorized
compensating transaction using the snapshot, checking current versions and
allocations and appending an audit event. Do not delete history or blindly
restore snapshots over subsequent payments. Reverting the code does not undo
the repaired financial projections.

## Local verification

Run `mvn verify` for the default suite. For real transaction/concurrency coverage,
start a dedicated local MongoDB with replica set name `quybpdev-test`, then run:

```sh
FUND_TEST_MONGODB_URI='mongodb://127.0.0.1:27028/?replicaSet=quybpdev-test' mvn verify
```

The opt-in tests refuse remote MongoDB targets, create a randomly named test
database and initialize the local replica set if needed. They exercise direct
transfer versus lookup, preview/apply/no-op, stale previews, rollback, duplicate
delivery, concurrent replicas, partial payments, credit and preservation of
adjustments. They do not connect to the shared fund database.
