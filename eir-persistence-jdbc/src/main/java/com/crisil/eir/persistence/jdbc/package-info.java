/**
 * JDBC implementations of the seven ports, over the verified DDL of {@code eir-persistence}.
 *
 * <h2>What this package is for</h2>
 *
 * <p>ADR-0011 states the gap in one sentence: the seven ports "are implemented in memory by
 * {@code eir-api}, which is enough to run the engine and not enough to make a replay a fair test of
 * bitemporality — a map answers the same thing at every as-at boundary". {@code eir-api}'s own
 * {@code Book} concedes it on the class: a replay against it reproduces, and that "is enough to make
 * a replay reproduce and not enough to make it a fair test of bitemporality".
 *
 * <p>Everything here follows from closing that gap. Every read carries both temporal predicates of
 * 04 § 5 — see {@link com.crisil.eir.persistence.jdbc.TemporalReads}, which is the class to read
 * first — and every query is exercised at two different {@code recordedAsAt} instants over the same
 * rows, because a query that ignored the boundary would pass every single-instant test.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><b>No writes.</b> All seven ports are reads and every connection is opened read-only. A
 *       source that could write would let a run mutate the version set it is reading as at.
 *   <li><b>No clock.</b> Time arrives as an {@code AsAtBoundary} argument. There is no
 *       {@code Instant.now()} in this package and a "read the latest" convenience would be one:
 *       ADR-0003's consequence is that a run which reads its own clock cannot be re-run.
 *   <li><b>No ORM.</b> ADR-0010 bans {@code jakarta.persistence} and Hibernate across every module
 *       and this module does not take that part of the exemption — only Flyway, and only for
 *       migration. The mapping is hand-written {@code ResultSet} reads, which is more code and is
 *       also the only way to be sure every money column went through {@code getBigDecimal}: see
 *       {@link com.crisil.eir.persistence.jdbc.Rows} for what a {@code getDouble} here would cost.
 * </ul>
 *
 * <h2>Gaps between 04 and the ports, and where they are handled</h2>
 *
 * <p>Three of the seven ports need facts 04 does not model, and V3 in this module's resources lands
 * them rather than deriving them from the engine's own output:
 *
 * <ul>
 *   <li>{@code CoreBankingFeed} needs the CBS's billed interest, and {@code GeneralLedgerSource} the
 *       GL's trial balance. 08's scope table puts both systems outside this engine. Deriving either
 *       from {@code period_balance} would make invariants RC-1 and SL-1 unfailable, which is the
 *       "field against itself" trap {@code CoreBankingFeed}'s javadoc names.
 *   <li>{@code ContractPeriodSource} needs the cash book's leg split and the collections system's
 *       suspense movements, for the same reason applied to invariant S3-1's fourth leg.
 *   <li>{@code ContractTerms} needs {@code disbursement_date}, {@code first_due_date} and
 *       {@code term_periods}, and 04 § 2.3 has no column for any of them. They are stored, not
 *       derived: {@code initial_recognition_date} is the ACPIR 23 commitment date for a commitment
 *       and "not the date of first drawdown", so the obvious derivation is wrong for a whole product
 *       class and wrong silently.
 * </ul>
 *
 * <p>V3's header carries the full argument for each. Nothing in V1 or V2 is modified.
 */
package com.crisil.eir.persistence.jdbc;
