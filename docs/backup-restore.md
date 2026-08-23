# Fund Management MongoDB Backup and Restore

The Helm chart creates a daily `fund-management-mongodb-backup` CronJob. It
writes compressed `mongodump` archives to the `fund-management-backups`
Longhorn PVC and removes archives older than 30 days. The job reads
`MONGODB_URI` from the existing runtime Secret; credentials are never stored in
this repository.

## Verify a backup

```bash
kubectl -n fund-management get cronjob fund-management-mongodb-backup
kubectl -n fund-management get jobs --sort-by=.status.startTime
kubectl -n fund-management logs job/<completed-job-name>
```

## Restore drill

1. Stop the backend deployment or scale it to zero during the restore window.
2. Create a short-lived `mongo:8.0` pod mounting `fund-management-backups` and
   the runtime Secret.
3. Run `mongorestore --uri="$MONGODB_URI" --db=fund_management --archive=<selected-archive> --gzip --drop`.
4. Restart the backend, wait for readiness, and validate the public overview
   balance plus a sample member/payment record.
5. Record the archive name, operator and validation result in the audit/runbook
   log. Do not paste connection strings into shell history, tickets or Git.

Run this drill at least once before declaring the backup acceptance criterion
complete.
