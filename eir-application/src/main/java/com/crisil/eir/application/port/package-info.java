/**
 * The ports: everything a run needs and this module cannot compute.
 *
 * <p><b>Why they are interfaces here rather than implementations somewhere.</b> 05 § 2 gives this
 * module "use-case orchestration, transaction boundaries, ports", and a port is the mechanism that
 * lets all three be framework-free: the module declares what it needs, and something outside it —
 * {@code eir-batch}, a test, a fixture — supplies it. The alternative is a module that reads a
 * database, at which point the orchestration cannot be tested without one and the reasoning about
 * a close becomes reasoning about a schema.
 *
 * <p><b>Every port returns versioned input, and that is not incidental.</b> 05 § 3.3 makes replay
 * the reason the data model is bitemporal: a replay reads "the contract version set as at the
 * original run's {@code recorded_at}, the policy and rule-set versions effective then, and the ECL
 * input version consumed then". A port that answered "what is the stage now" rather than "what was
 * the stage as at this boundary" would make DT-1 unsatisfiable, and it would do so silently —
 * every figure would be internally consistent and none would reproduce. So each port takes the
 * as-at boundary as an argument rather than reading a clock, which is also what keeps
 * {@code DeterminismTest}'s ban satisfiable one module down.
 *
 * <p>None of these is a repository in the persistence sense. {@code eir-persistence} is DDL with no
 * ORM (ADR-0010), so the implementations that will satisfy these interfaces do not exist yet — and
 * the run is testable against fixtures in the meantime, which is the point of declaring them.
 */
package com.crisil.eir.application.port;
