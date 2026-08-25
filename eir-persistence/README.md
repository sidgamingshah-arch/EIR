# eir-persistence

The schema of [04](../docs/04-data-model.md) as executable PostgreSQL 16 DDL, under
`src/main/resources/db/migration/`.

## Why there is no ORM here

The module carries **no compile dependency** — not JPA, not Flyway, not a driver. The root pom's
enforcer bans `jakarta.persistence`, Hibernate, Spring and Jackson across every module
(`ban-frameworks-in-calculation-path`), so wiring an ORM would require restructuring that ban.
That is a shared root-pom edit and it deserves its own change rather than riding along with the
schema.

Migrations are numbered in Flyway's `V<n>__<description>.sql` convention so that wiring Flyway
later is an addition, not a rewrite.

## Physical design (04 § 4)

| Concern | Decision |
|---|---|
| Engine | PostgreSQL 16+ |
| Money | `NUMERIC(24,6)` — six decimals holds working values above presentation scale with no floating point |
| Rates | `NUMERIC(20,12)` — matches the 12dp storage policy of [03 § 1](../docs/03-calculation-spec.md) exactly |
| Partitioning | `PERIOD_BALANCE`, `JOURNAL_ENTRY`, `CASHFLOW_LINE` range-partitioned by `period_id` |
| Bitemporality | `valid_from`/`valid_to` (business time) and `recorded_at`/`superseded_at` (system time), per 04 § 5 |

## Verifying the DDL

The DDL is verified **by execution against a real cluster**, not by review. PostgreSQL 16 server
binaries are present in this environment; the recipe below is known to work.

`initdb` refuses to run as root, and the `postgres` user cannot read the session scratchpad, so
the cluster lives under `/var/tmp` and is owned by `postgres`:

```sh
PG=/var/tmp/eirpg                      # any empty dir under /var/tmp
PGBIN=/usr/lib/postgresql/16/bin
rm -rf "$PG" && mkdir -p "$PG" && chown postgres:postgres "$PG" && chmod 700 "$PG"

su postgres -s /bin/bash -c "$PGBIN/initdb -D $PG/data -U postgres --auth=trust"
su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D $PG/data -o '-p 5433 -k $PG' -l $PG/pg.log start"

# migrations must be world-readable for the postgres user to open them
cp src/main/resources/db/migration/*.sql /var/tmp/ && chmod 644 /var/tmp/V*.sql
su postgres -s /bin/bash -c \
  "$PGBIN/psql -h $PG -p 5433 -U postgres -d postgres -v ON_ERROR_STOP=1 -f /var/tmp/V1__core.sql"
```

**A zero exit status is not the check.** `psql` exiting 0 proves the file parsed, not that the
schema is what 04 specifies. Verify the three properties that actually matter:

```sh
# 1. partitioned tables report relkind 'p', their partitions 'r'
$PGBIN/psql ... -tAc "select relname, relkind from pg_class
                      where relname like 'period_balance%' order by 1;"

# 2. money and rate columns carry the specified precision and scale
$PGBIN/psql ... -tAc "select column_name, numeric_precision, numeric_scale
                      from information_schema.columns
                      where table_name = 'period_balance' and numeric_scale is not null;"

# 3. every expected table exists
$PGBIN/psql ... -tAc "select table_name from information_schema.tables
                      where table_schema = 'public' order by 1;"
```

Expected from (1): `period_balance|p`. Expected from (2): `NUMERIC(24,6)` on every money column
and `NUMERIC(20,12)` on every rate column — a money column that came out `(24,2)` parses fine and
silently truncates working precision, which is exactly the class of defect this check exists to
catch.

Stop the cluster with
`su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D $PG/data stop"`.
