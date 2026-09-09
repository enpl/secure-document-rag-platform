#!/bin/sh
# ============================================================
# Secure Document Vault (SDV)
# Fresh-volume initialization: create the restricted runtime login `sdv`.
#
# Runs only once, automatically, by the official postgres entrypoint's
# docker_process_init_files, and only when the data directory is empty
# (a fresh named volume). It is never re-run automatically against an
# already-initialized data directory - see infra/secrets/README.md for
# the manual reconciliation procedure for an existing volume.
#
# May run either executed (if the bind mount preserves the executable
# bit) or sourced with `.` (if it does not, e.g. some Windows bind-mount
# setups) - see docker-entrypoint.sh's docker_process_init_files. This
# script is written to behave correctly either way: POSIX `sh` only,
# `set -eu` with no `set -x` (never echo the secret), and a plain `exit`
# on failure, which aborts container initialization in both cases.
#
# IMPORTANT: this file must be tracked/checked out with LF line endings.
# A POSIX shell rejects CRLF line endings (e.g. a trailing '\r' after
# `#!/bin/sh` breaks the shebang). The repository's `.gitattributes` carries
# `infra/initdb/*.sh text eol=lf`, which forces this on checkout/export
# regardless of a checkout machine's core.autocrlf setting - verified with a
# disposable, isolated test repository (see the handoff).
#
# `sdv` privileges: NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
# NOBYPASSRLS, never an object owner, no schema CREATE. Table-level
# SELECT/INSERT/UPDATE/DELETE grants are applied separately by the
# Flyway afterMigrate callback (backend/src/main/resources/db/callback/
# afterMigrate__grant_runtime_privileges.sql) once application tables
# exist - none exist yet at this point in fresh initialization.
# ============================================================
set -eu

SECRET_FILE=/run/secrets/sdv_runtime_password

if [ ! -f "$SECRET_FILE" ]; then
	echo "FATAL: $SECRET_FILE does not exist (missing Compose secret 'sdv_runtime_password')" >&2
	exit 1
fi

if [ ! -r "$SECRET_FILE" ]; then
	echo "FATAL: $SECRET_FILE is not readable" >&2
	exit 1
fi

if [ ! -s "$SECRET_FILE" ]; then
	echo "FATAL: $SECRET_FILE is empty" >&2
	exit 1
fi

# Read the secret into an environment variable rather than a shell
# positional/argv value, so it never appears in `ps`, and hand it to
# psql via \getenv rather than string-interpolating it into SQL text,
# so it never appears in psql's own echoed output or error messages.
#
# NOTE: command substitution strips ALL trailing newlines from its output,
# not just one. The `[ -s "$SECRET_FILE" ]` check above only proves the file
# has nonzero byte size - a file containing nothing but newline characters
# (e.g. a stray blank line) is nonempty by that check yet produces an EMPTY
# value here. Validate the value actually read, below, rather than trusting
# the earlier file-size check alone. This does not trim or otherwise alter
# a real password's characters - it only rejects the degenerate case where
# nothing but newlines was read, using the same extraction already
# documented in infra/secrets/README.md.
SDV_RUNTIME_PASSWORD="$(cat "$SECRET_FILE")"

if [ -z "$SDV_RUNTIME_PASSWORD" ]; then
	echo "FATAL: $SECRET_FILE produced an empty value after reading (it may contain only newline characters)" >&2
	exit 1
fi

export SDV_RUNTIME_PASSWORD

# POSTGRES_DB is a shell environment variable exported by the entrypoint;
# it is NOT automatically visible to psql as a `:"POSTGRES_DB"` variable.
# It must be passed explicitly with -v, same as the bootstrap username.
psql -v ON_ERROR_STOP=1 --no-psqlrc \
	--username "$POSTGRES_USER" \
	--dbname "$POSTGRES_DB" \
	-v db_name="$POSTGRES_DB" \
	<<'SQL'
\getenv sdv_runtime_password SDV_RUNTIME_PASSWORD

DO $do$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdv') THEN
		CREATE ROLE sdv WITH LOGIN
			NOSUPERUSER
			NOCREATEDB
			NOCREATEROLE
			NOREPLICATION
			NOBYPASSRLS;
	END IF;
END
$do$;

-- ALTER ROLE ... PASSWORD accepts a parameter as of PostgreSQL 16's
-- extended SQL/PSM-style syntax is not universally available, so the
-- password is bound through psql's :'variable' quoting (SQL-literal
-- quoted), never concatenated as raw text.
ALTER ROLE sdv WITH PASSWORD :'sdv_runtime_password';

-- Declaratory only: PUBLIC already holds CONNECT on every database and
-- USAGE on schema "public" by default in a fresh PostgreSQL cluster.
-- Kept explicit so this script's intent still holds if that PUBLIC
-- baseline is ever tightened later (see infra/secrets/README.md).
GRANT CONNECT ON DATABASE :"db_name" TO sdv;
GRANT USAGE ON SCHEMA public TO sdv;
SQL

# If this script is sourced rather than executed (docker_process_init_files sources it
# with `.` when the bind mount does not preserve the executable bit - see the file
# header), `export` above modifies the ENTRYPOINT's own shell, not a private subshell:
# without this, SDV_RUNTIME_PASSWORD would remain visible to every later init file
# entrypoint processes afterward, regardless of how THAT file is itself run - an
# executable later script is forked as a child process and inherits it as an ordinary
# exported environment variable; a non-executable later script is sourced into this
# same shell (not forked at all) and would see it directly, still in scope, with no
# "inheritance" involved. Either way, explicitly unsetting it here removes it from this
# shell before any later init file runs, so neither case can observe it - whether this
# script itself ran executed or sourced. A no-op, harmless either way, if the variable
# is already unset (e.g. it was never exported because the script exited earlier on a
# validation failure).
unset SDV_RUNTIME_PASSWORD
