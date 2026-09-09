# infra/secrets/

Local-only Compose file secrets for `infra/docker-compose.dev.yml`. This
directory is `.gitignore`d except for this README - no secret file here is
ever committed, and this file itself never contains a real value.

## Required files (create locally, do not commit)

| File | Consumed by | Purpose |
|---|---|---|
| `postgres_password.txt` | Postgres image (`POSTGRES_PASSWORD_FILE`) | Password for the bootstrap superuser `sdv_user`. |
| `sdv_runtime_password.txt` | `infra/initdb/10-create-runtime-role.sh` | Password for the restricted runtime login `sdv`. |

Create them with a single trailing newline stripped or not - either is fine,
the init script reads the whole file as the password. A **missing** file
makes Compose fail to start the `postgres` service (`POSTGRES_PASSWORD_FILE`
points nowhere); an **empty** file is caught explicitly by the init script,
which exits non-zero and aborts container initialization rather than
creating `sdv` with no password.

Example (PowerShell), using two locally generated throwaway values - never
reuse the value across environments and never paste it into chat/logs:

```powershell
# from infra/
Set-Content -NoNewline -Path .\secrets\postgres_password.txt -Value '<local-only-value>'
Set-Content -NoNewline -Path .\secrets\sdv_runtime_password.txt -Value '<local-only-value>'
```

## How these values reach the application

Compose secrets only populate files inside the **postgres** container
(`/run/secrets/*`); they do **not** automatically become environment
variables for the Spring Boot process, in Compose or otherwise. Docker
Compose has no built-in mechanism that maps a `secrets:` file to another
service's `SPRING_DATASOURCE_PASSWORD`/`SPRING_FLYWAY_PASSWORD`. The backend
process needs its own copies of the same plaintext values, supplied
separately - e.g. via your IDE run configuration's environment variables, a
local (git-ignored) `.env` used by `docker compose --env-file`, or your
shell profile. `SPRING_DATASOURCE_USERNAME`/`SPRING_FLYWAY_USER` and the URL
defaults are not secrets and already have safe defaults in
`application-local.yml` / `application-compose.yml`; only the two password
variables need a value from you locally, and both fail loudly (placeholder
resolution error) rather than silently falling back if left unset - see the
handoff for the observed behavior of an empty vs. missing value.

**Changing `postgres_password.txt` or `sdv_runtime_password.txt` after the
Postgres data volume already exists does *not* rotate the corresponding
in-database password.** Compose secrets are only read by the Postgres
entrypoint/init scripts on first initialization of an empty data directory
(see "Recovering from partial initialization" below). To actually rotate a
password on an already-initialized volume, connect as `sdv_user` and run
`ALTER ROLE ... WITH PASSWORD ...` yourself, then update the corresponding
secret file and your local Spring environment variables to match.

## Recovering from partial initialization

`infra/initdb/*.sh` and any `*.sql` files under `infra/initdb/` are only
executed by the official Postgres entrypoint **the first time the data
directory is empty** (i.e. the very first `docker compose up` against a
brand-new `sdv-postgres-data` volume). If a container is started, the data
directory becomes non-empty, and *then* an init script is discovered to be
missing, misconfigured, or fails partway through (e.g. this project adds
`10-create-runtime-role.sh` to a volume that was already initialized
without it), the entrypoint **does not automatically re-run** any init
script on subsequent restarts - init scripts under
`/docker-entrypoint-initdb.d/` are skipped entirely once `$PGDATA` is
non-empty.

To recover, either:

- **Fresh volume (development-disposable, preferred for a local dev
  volume with no data worth keeping):** stop the stack, remove the named
  volume (`sdv-postgres-data`), and start again so init scripts run in
  full against an empty directory. This destroys all local data in that
  volume.
- **Existing volume you want to keep:** run the reconciliation SQL
  manually as `sdv_user` against the running database - create the `sdv`
  role (same `CREATE ROLE ... LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS` statement the init script uses, with your own
  chosen password), then invoke
  `backend/src/main/resources/db/callback/afterMigrate__grant_runtime_privileges.sql`
  once by hand (or simply restart the application against that database
  in the `local`/`compose` profile so the Flyway `afterMigrate` callback
  applies it automatically on the next successful migration run).

This project does not implement or promise an automatic init-script rerun
mechanism - do not assume one exists.
