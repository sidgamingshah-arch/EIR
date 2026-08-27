# ADR-0010 — The framework ban fails closed, and exemptions name their authority

**Status:** Accepted

## Context

The root pom carries a `maven-enforcer` execution, `ban-frameworks-in-calculation-path`, that
rejects `org.springframework*`, `org.springframework.boot`, `jakarta.persistence`,
`com.fasterxml.jackson.core` and `org.hibernate*` — transitively, with `<fail>true</fail>`. It sits
in `<build><plugins>`, so **every module inherits it**.

[05 § 2.1](../05-architecture.md#21-why-eir-domain-and-eir-calc-are-framework-free) describes it
differently:

> A build-failing enforcement rule (`maven-enforcer` banned-dependencies) rejects any framework
> dependency **in these two modules**, alongside the `double`/`float` lint rule.

"These two" are `eir-domain` and `eir-calc`. So the build **over-implements the specification**: the
commitment is about two modules and the enforcement covers all of them. That is not a defect — it
has been free so far, because nothing has needed a framework — but it is why
[08 Phase 2](../08-roadmap.md) records `eir-persistence` shipping as DDL with no ORM, and why
Phase 5 cannot start: [ADR-0007](0007-spring-batch-for-runs.md) mandates Spring Batch for runs, and
`eir-batch` cannot declare the dependency while the ban applies to it.

Recognising the over-implementation matters, because it changes what the decision *is*. Relaxing the
ban outside the calculation path is not weakening an architectural commitment. It is bringing the
build back to the commitment as written.

## Options

**A. Scope the ban to `eir-domain` and `eir-calc`.** Move the execution to `<pluginManagement>` and
declare it in exactly those two poms — the literal reading of 05 § 2.1.

**B. Invert to opt-in.** No inherited ban; each module that wants protection asks for it.

**C. Keep the ban inherited, and require an explicit, self-documenting opt-out.** A module needing a
framework disables the inherited execution in its own pom and states the authority that permits it.

## Decision

**Option C.** Two arguments decide it, and both are about what happens on the day somebody adds a
module rather than about today's dependency graph.

**Under A and B, adding `eir-batch` is a silent unbanning.** A new module inherits nothing, so
Spring arrives with no diff anywhere that says a framework boundary was crossed. Under C the same
change requires writing the exemption into `eir-batch/pom.xml`, where a reviewer reading that pom
sees it. This codebase has repeatedly found that the failure worth engineering against is not the
wrong decision but the *undocumented* one — and it has recorded the general form of it three times:
a control that fails open is worse than one that is absent, because it reports a green.

**A would drop `eir-policy`'s protection, which is not incidental any more.** 05 § 2.1 named two
modules when `eir-policy` did not exist. It now holds the publication sites for eleven invariants —
PG-1, PV-1, RT-1, RS-1, PL-1, PL-2, TF-1, BM-1, LC-1, DE-1, TM-1 — and is as much audit surface as
`eir-calc`. Scoping the ban to the two modules the document happens to name would remove a
protection that has become load-bearing since it was written.

`eir-persistence` is the one module where the exemption is already earned and not yet taken; it stays
banned until an ORM is actually wired, because an exemption for a dependency nobody has added is the
same undocumented licence in the other direction.

### The mechanism

A module exempting itself disables the inherited execution and states why:

```xml
<!-- FRAMEWORK EXEMPTION: ADR-0007 mandates Spring Batch for partitioned runs. -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>ban-frameworks-in-calculation-path</id>
      <phase>none</phase>
    </execution>
  </executions>
</plugin>
```

The `FRAMEWORK EXEMPTION:` marker is not a comment convention resting on goodwill.
`FrameworkBanTest` in `eir-domain` parses every module pom and asserts three things:

1. the root still declares the execution, transitively and with `<fail>true</fail>`;
2. `eir-domain` and `eir-calc` carry **no** override of it — the two modules 05 § 2.1 commits to
   cannot be exempted, whatever a future pom says;
3. every module that *does* override it carries a `FRAMEWORK EXEMPTION:` line with a stated
   authority.

(3) is the part worth having. Without it the mechanism is a convention, and a convention that a
build does not check is a comment.

## Consequences

`eir-batch`, `eir-api` and `eir-app` become one-pom changes rather than a shared-pom argument. The
audit surface is protected by a test rather than by a plugin's inheritance rules, which is stronger:
inheritance is silent when a module opts out, and the test is not.

The cost is that adding a legitimately framework-free module gains nothing automatically — it
inherits the ban, which is the intent — while a module that needs a framework carries four lines of
pom and one sentence of justification. That is the trade this ADR is buying.

**What this does not change.** The ban is not the reason `eir-domain` and `eir-calc` are readable as
mathematics; it is the check that they stay that way. 05 § 2.1's three reasons — testability of the
thing that matters, the audit surface, and longevity against framework generations — are unaffected,
and none of them extends to a batch runner.
