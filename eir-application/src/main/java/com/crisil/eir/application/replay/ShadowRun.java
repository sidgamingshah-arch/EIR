package com.crisil.eir.application.replay;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.replay.ReplayRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The shadow table: what a replay produced, reduced to the figures DT-1 byte-compares (05 § 3.3,
 * "output goes to a shadow table and is compared byte-for-byte with the published figures").
 *
 * <h2>Nothing here rescales anything</h2>
 *
 * <p>The single most important line in this file is the one that is absent: no call to
 * {@link Money#atPresentationScale()}. A figure's scale is a property of <em>where in the pipeline
 * it was reduced</em> — {@code Money.atPresentationScale} is "the only place a money value loses
 * precision, and it is called once per persisted figure" — so a run that reduces at a different
 * point, or skips the reduction because the value was already round, publishes the same number at
 * a different scale. That is a {@code DiscrepancyKind.SCALE_ONLY} finding worth exactly zero
 * rupees and it is still a different published artefact. A reduction applied here would erase the
 * one defect the whole package exists to catch: both sides would arrive at two decimals and the
 * control would report a clean night.
 *
 * <h2>Which figures, and which deliberately not</h2>
 *
 * <p>Two kinds, both per contract, both at the grain a period actually publishes:
 *
 * <ul>
 *   <li>the closing gross carrying amount — {@code PERIOD_BALANCE}, the figure a disclosure and a
 *       Schedule 13 extract are built from;</li>
 *   <li>every journal line, in order, keyed by its position, account and side — {@code JOURNAL_
 *       ENTRY}, the figure the GL receives.</li>
 * </ul>
 *
 * <p><b>No portfolio totals.</b> The obvious addition is a run-level sum of closing balances, and
 * it would detect nothing: both sides' totals are derived from the same per-contract figures this
 * map already compares, so a total can only differ where a contract figure already differs. It
 * would inflate the denominator {@code ReplayComparison.describe()} reports and give one
 * condition two mechanisms — which is the defect this codebase keeps rediscovering. A wider replay
 * population, the case a total might seem to catch, already surfaces per contract as
 * {@code ABSENT_FROM_PUBLICATION}.
 *
 * <p><b>Nothing for a quarantined contract.</b> A contract the barrier isolated produced no
 * figures — {@link ContractResult} refuses to carry any — so it contributes no keys, and that is
 * the right answer for a byte comparison of published figures. All four quarantine combinations
 * are still visible to DT-1: computed at close and quarantined on replay shows up as
 * {@code MISSING_FROM_REPLAY}, the reverse as {@code ABSENT_FROM_PUBLICATION}, and quarantined on
 * both sides contributes nothing to either side, which is a faithful reproduction of a period that
 * published nothing for that contract. The case where <em>every</em> contract is quarantined and
 * the comparison therefore has no figure at all is not left to look like a pass: see
 * {@link ReplayCoverage}.
 */
public final class ShadowRun {

    /** The prefix {@code PeriodStatement}'s convention uses for a row-level figure key. */
    private static final String CONTRACT = "CONTRACT:";

    private ShadowRun() {
    }

    /**
     * The replay's output as a {@link ReplayRun}, ready for {@code ReplayComparison}.
     *
     * @param shadowRunId  the replay's own run id
     * @param publishedRun the run being reproduced; recorded as {@code replay_of_run_id} so the
     *                     shadow row names its reference, per {@code fk_amortisation_run_replay_of}
     * @param output       what the batch job produced
     */
    public static ReplayRun of(String shadowRunId, PublishedRun publishedRun, RunOutput output) {
        Objects.requireNonNull(shadowRunId, "shadowRunId");
        Objects.requireNonNull(publishedRun, "publishedRun");
        Objects.requireNonNull(output, "output");
        return ReplayRun.replayOf(
            shadowRunId,
            publishedRun.runId(),
            publishedRun.periodId(),
            figures(output.contracts()),
            output.policyVersionsConsulted());
    }

    /**
     * Every published figure the results carry, keyed as {@link #closingGcaKey} and
     * {@link #journalLineKey} name them.
     *
     * <p>Iteration follows the result list, and the result list follows the population order the
     * run was given ({@code FailureIsolation.runBatch} keeps insertion order for exactly this
     * reason). {@code ReplayRun.figureKeys()} sorts before comparing, so the report's order does
     * not depend on this — but a duplicate would, and a duplicate is refused below.
     */
    public static Map<String, Money> figures(List<ContractResult> results) {
        Objects.requireNonNull(results, "results");
        Map<String, Money> figures = new LinkedHashMap<>();
        for (ContractResult result : results) {
            Objects.requireNonNull(result, "contract result");
            if (!result.isComputed()) {
                continue;
            }
            if (result.closingGca() != null) {
                // Null is possible — ContractResult does not require a closing balance on a
                // computed contract — and it is not silently tolerated: a contract the close
                // published a balance for and the replay did not produce one for surfaces as
                // MISSING_FROM_REPLAY, which is a DT-1 breach naming the contract. Inventing a
                // zero here would turn that into a VALUE_DIFFERS against a figure nobody
                // computed, and inventing nothing at all on both sides would hide it.
                put(figures, closingGcaKey(result.contractId()), result.closingGca());
            }
            JournalEntry journal = result.journal();
            List<JournalLine> lines = journal.lines();
            for (int i = 0; i < lines.size(); i++) {
                JournalLine line = lines.get(i);
                put(figures,
                    journalLineKey(result.contractId(), i + 1, line.accountCode(),
                        line.side().name()),
                    line.amount());
            }
        }
        return figures;
    }

    /**
     * The figure key for a contract's closing gross carrying amount.
     *
     * <p>Public because the published side has to be projected under the <em>same</em> convention
     * for the comparison to join at all, and a convention that lives only inside this method is a
     * convention the persistence layer will guess at. It fails loudly if it is guessed wrong —
     * every figure becomes a {@code MISSING_FROM_REPLAY} plus an {@code ABSENT_FROM_PUBLICATION}
     * pair and DT-1 reports twice the population — which is the right failure mode, but naming the
     * builders is cheaper than triaging it.
     *
     * <p>{@code CONTRACT:id:column}, which is the row-level convention
     * {@code PeriodStatement}'s javadoc records.
     */
    public static String closingGcaKey(String contractId) {
        return CONTRACT + require(contractId) + ":closing_gca";
    }

    /**
     * The figure key for one journal line.
     *
     * <p>Keyed by ordinal as well as account and side, because one entry may post twice to one
     * account on one side — an interest accrual and a fee amortisation both crediting income —
     * and a key that collapsed them would compare a sum against a sum and lose which line moved.
     * The ordinal is 1-based to match how a journal reads on paper.
     */
    public static String journalLineKey(
        String contractId, int lineOrdinal, String accountCode, String side) {
        return CONTRACT + require(contractId) + ":journal:" + lineOrdinal + ":"
            + require(accountCode) + ":" + require(side);
    }

    private static void put(Map<String, Money> figures, String key, Money amount) {
        Money clash = figures.putIfAbsent(key, Objects.requireNonNull(amount, "amount for " + key));
        if (clash != null) {
            // A defect in the run rather than in a contract, so it is refused rather than filed:
            // FailureIsolation takes the same line on a computation that returns null. Two
            // amounts under one key would make "what the replay produced" depend on which was
            // found first, and a comparison that depends on iteration order is not a byte
            // comparison. FailureIsolation.runBatch already refuses a duplicate contract id
            // before any work starts; this catches the same condition arriving by another route.
            throw new IllegalStateException(
                "the run produced two figures under key '" + key + "': " + clash + " and "
                    + amount + "; one key naming two figures makes the byte comparison depend on"
                    + " which was found first");
        }
    }

    private static String require(String value) {
        Objects.requireNonNull(value, "figure key component");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                "a figure key component is blank; an unnamed figure cannot be matched against the"
                    + " published one it is supposed to reproduce");
        }
        return value.strip();
    }
}
