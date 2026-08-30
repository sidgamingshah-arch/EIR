# ADR-0011 — JDBC persistence behind a profile, so the offline build survives

**Status:** accepted
**Date:** 2026-08-29
**Supersedes nothing. Depends on [ADR-0010](0010-framework-ban-fails-closed.md).**

## Context

`eir-persistence` has shipped as verified PostgreSQL DDL since Phase 2, with no Java that reaches a
database. The seven ports in `eir-application` are implemented in memory by `eir-api`, which is
enough to run the engine and not enough to make a replay a fair test of bitemporality — a map
answers the same thing at every as-at boundary.

Building the JDBC adapters needs the PostgreSQL driver and Flyway. Neither is in the local
repository, and this repository has protected one property since Phase 2: **`mvn -o install` builds
the whole reactor with no network**, because an air-gapped bank CI has no Central.

Putting the JDBC module in `<modules>` would end that for every module at once. A worker building an
unrelated endpoint would find the reactor failing to resolve a driver it has no interest in.

## Decision

The JDBC adapters live in `eir-persistence-jdbc`, declared **inside a `jdbc` profile** rather than in
`<modules>`.

- `mvn install` — the default reactor, offline-capable, no JDBC module.
- `mvn -Pjdbc install` — adds the module. Needs network once, then it is cached like everything else.

The module takes ADR-0010's framework exemption for Flyway, with the authority named in its pom.

## Consequences

**Good.** The offline guarantee holds unconditionally for the engine. A contributor who never
touches persistence never resolves a database driver. The profile is one flag, and CI can run both
paths to prove both properties.

**Bad, and worth stating.** A module outside the default reactor is a module whose compilation errors
nobody sees by default. That is a real cost: it is exactly how a module rots. The mitigation is that
the merge gate runs both `mvn -B -o install` and `mvn -B -Pjdbc install`, and neither is optional.

**Rejected: the inverse arrangement** — JDBC active by default with an `offline` profile excluding
it. It reads better in a plan and it is wrong in practice: `activeByDefault` is disabled the moment
any other profile is named, which makes the offline path depend on a Maven subtlety rather than on a
declared list. The failure mode is a CI that silently stops building persistence.

**Rejected: vendoring the driver.** A checked-in jar is a supply-chain artefact nobody reviews and a
version nobody upgrades.
