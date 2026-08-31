# `eir-persistence-jdbc`

Real persistence behind the seven ports of `eir-application`, over the verified PostgreSQL DDL in
`eir-persistence`, plus the Flyway wiring that brings a fresh database up. See
[ADR-0011](../docs/adr/0011-jdbc-persistence-behind-a-profile.md) for why it sits behind a profile.

**Read [`docs/08`'s Phase 2 qualifications](../docs/08-roadmap.md) before wiring this to a run.** The
module builds and its live suite passes, and it carries five recorded latent defects that fire the
moment anything constructs `JdbcPorts` for a close. Nothing does today. The most serious is that both
sides of invariant RC-1 would come from one column of one table.

## Building it

Not in the default module list: the PostgreSQL driver and Flyway are not guaranteed to be in the
local repository, and every other module must keep building with `mvn -o install`.

```
mvn -B -Pjdbc install -pl eir-persistence-jdbc
```

Drop `-B -Pjdbc` for the whole reactor with the profile on: `mvn -B -Pjdbc install`.

## Running the live-cluster suite

The three classes tagged `live-db` — `SchemaVerificationLiveTest`, `BitemporalReadLiveTest`,
`RemainingPortsLiveTest` — need a real PostgreSQL 16 cluster. They are excluded from the default
build by group, not by an assumption: **there is no skip mechanism anywhere in this module**, so a
green build can never be mistaken for a verified one. A build that cannot reach a database fails
nothing and skips nothing, and a build asked for the live group and given no database fails loudly.

PostgreSQL cannot run as root, so the cluster runs as the `postgres` user with its data directory
somewhere that user can traverse:

```
export PATH=/usr/lib/postgresql/16/bin:$PATH
PG=/var/lib/postgresql/eirtest
rm -rf "$PG" && mkdir -p "$PG" && chown -R postgres:postgres "$PG"
su postgres -s /bin/bash -c "PATH=/usr/lib/postgresql/16/bin:\$PATH initdb -D '$PG/data' -U postgres --auth=trust"
su postgres -s /bin/bash -c "PATH=/usr/lib/postgresql/16/bin:\$PATH pg_ctl -D '$PG/data' -o \"-p 5443 -k $PG -c listen_addresses=127.0.0.1\" -l '$PG/pg.log' start"
psql -h 127.0.0.1 -p 5443 -U postgres -c 'CREATE DATABASE eir;'
```

Then:

```
mvn -B -o -Pjdbc test -pl eir-persistence-jdbc \
    -Deir.surefire.excludedGroups=none \
    -Deir.jdbc.url=jdbc:postgresql://127.0.0.1:5443/eir \
    -Deir.jdbc.user=postgres
```

`LiveDatabase` rebuilds the schema through Flyway on each run, so the database need only exist.

**Last measured:** 53 tests offline, **100 tests against PostgreSQL 16.13**, 0 failures, 0 errors,
0 skipped.

## What the schema actually contains

Verified from `information_schema` and `pg_class` rather than from a migration exiting 0 — the
distinction matters, because this repository has already shipped a pair of migrations that both
reported success while all seventeen of V2's foreign keys into V1's tables silently failed to form:

| | |
|---|---|
| tables | 67 |
| partitioned tables (`relkind='p'`) | 3 — `cashflow_line`, `journal_entry`, `period_balance` |
| check constraints | 294 |
| foreign keys | 167, of which **23 run from a V2 table into a V1 table** |
| money columns `NUMERIC(24,6)` | 302 |
| rate columns `NUMERIC(20,12)` | 37 |
| float / double / real columns | **0** |

The only unconstrained `numeric` columns are four `SUM()` results in views, which is correct — a sum
of `NUMERIC(24,6)` needs the headroom.

`period_balance_p202712` is bounded `FROM (202712) TO (202801)`, which is the December-to-January roll
handled correctly: `202713` is not a month, and `PeriodId`'s javadoc is written about exactly that
non-contiguity.
