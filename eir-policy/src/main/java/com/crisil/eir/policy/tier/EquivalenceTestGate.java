package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The TG-1 evaluator: whether a proposed Tier 3 measurement may stand (FR-411, FR-412).
 *
 * <p>Until now TG-1 was a label — {@code InvariantId.TG_1}, "Tier 3 equivalence test in date",
 * with nothing that computed it. This is the logic. Given something FR-107 assignment has
 * proposed for Tier 3 and a reporting date, it answers two questions in a fixed order and
 * returns the answer rather than throwing it:
 *
 * <ol>
 *   <li><b>FR-412 — is approximation forbidden outright?</b> Zero-coupon and deep-discount
 *       instruments are refused Tier 3 at any tenor. Checked first, and <em>not</em> curable by
 *       an equivalence test however current.
 *   <li><b>FR-411 — is there a current equivalence test on file?</b> A test exists, was
 *       performed on or before the reporting date, is inside its annual window, and its
 *       documented delta is within the Board-approved threshold. Anything else demotes the
 *       population to Tier 2.
 * </ol>
 *
 * <h2>Why returned and not thrown</h2>
 *
 * <p>Because the specified consequence is a demotion, not a stop. 03 § 10.2: "An out-of-date
 * test demotes the population to Tier 2." Tier 2 is <em>more</em> expensive and <em>more</em>
 * correct than Tier 3 — a pool EIR under the ACPIR 51 group presumption rather than contractual
 * rate plus straight-line fee accretion — so the right response to missing evidence is to
 * measure properly, not to quarantine a contract that is perfectly measurable.
 * {@code ExceptionCategory.STALE_EQUIVALENCE_TEST} carries {@code stopsTheContract() == false}
 * for exactly this reason, one of only two of the ten categories that does. The engine still
 * raises the exception, because a population silently changing measurement basis between one
 * close and the next is precisely the thing a close should surface, and it still blocks the
 * close until somebody acknowledges it.
 *
 * <h2>Why FR-412 is checked before the test, and cannot be cured by one</h2>
 *
 * <p>{@code Case 9} is the argument and it is worth restating in full, because the ordering of
 * these two checks is the whole substance of FR-412. A 15-year zero-coupon bought at 315,241.70
 * against a face of 1,000,000.00 has 684,758.30 of discount to accrete. Straight line takes
 * 45,650.55 a year, every year. The EIR method takes 25,219.34 in year one, 50,413.57 in year
 * ten and 74,074.07 in year fifteen. Straight line therefore <b>overstates year-one income by
 * 81.0%</b> and understates the final year by <b>38.4%</b> — and the two columns total
 * <b>684,758.30 exactly</b>, to the paisa, on both methods.
 *
 * <p>That exact tie is not a reassurance, it is the defect. An error that nets to zero over the
 * life of the instrument is invisible to every control that looks at cumulative figures, and
 * material in every single reporting period. Which means an equivalence test cannot rescue this
 * category: the comparison of 03 § 10.2 is a solved-versus-approximated delta, and on a
 * zero-coupon the lifetime delta is zero. A test that measured the thing it is defined to
 * measure would <em>pass</em>, on an instrument where the annual error is 81%. So the refusal
 * has to come first, before the evidence is consulted, and no quantity of current, in-date,
 * within-threshold evidence may reverse it. That is the ordering below, and
 * {@code Case 9} states the conclusion directly: "No approximation is permissible for
 * zero-coupon and deep-discount instruments at any tenor."
 *
 * <h2>The T-bill tension, and how it is resolved</h2>
 *
 * <p>03 § 10's Tier 3 row lists, by name, "T-bills, CP, CD, call/notice money, TREPS, market
 * repo". Every one of the first three is a discount instrument with no coupon leg, so FR-412
 * refuses Tier 3 for the very products 03 § 10 puts in Tier 3. The two statements cannot both be
 * applied as written and this gate resolves the conflict in favour of 03 § 10.3 and FR-412:
 * a 91-day T-bill is refused Tier 3 and measured at Tier 2.
 *
 * <p>The reasoning, since the resolution is not cost-free. FR-412 and 03 § 10.3 are absolute and
 * say "at any tenor" twice; the Tier 3 table is a population sketch by tenor band and product
 * family, which is the general rule, and § 10.3 is headed "Where approximation is never
 * permitted", which is the exception to it. Taking the specific over the general is the ordinary
 * reading, and it is the only reading under which "at any tenor" does any work at all — every
 * Tier 3 population is by construction inside twelve months, so a tenor carve-out for short
 * instruments would empty FR-412 entirely.
 *
 * <p>It is also the cheap direction to be wrong in. On a 91-day bill the front-loading error is
 * small in rupees, and the price of refusing the shortcut is a pool EIR the engine computes
 * anyway for the retail book. On a 15-year zero-coupon it is 81% of year-one income. A rule that
 * errs towards measuring properly costs compute; a rule that errs the other way is the Cambodia
 * failure mode, and 03 § 10 opens by naming it. What this gate will not do is split the
 * difference with an undocumented tenor threshold, because an undocumented materiality boundary
 * is the specific practice 03 § 10.2 exists to forbid. If the bank wants the money-market
 * discount instruments back in Tier 3, that is a Board-approved carve-out with a number
 * attached, recorded as policy — not a default buried in an evaluator.
 */
public final class EquivalenceTestGate {

    /**
     * The tier a refused or unevidenced Tier 3 population falls back to.
     *
     * <p>Named once rather than written at each of the five demotion sites. FR-411 and 03 § 10.2
     * both say Tier 2, and 03 § 10.1 is what Tier 2 then means: a pool EIR under the ACPIR 51
     * group presumption, with the quarterly back-test and closed-cohort discipline that come
     * with it.
     */
    public static final MaterialityTier DEMOTION_TIER = MaterialityTier.TIER_2;

    private final Map<String, List<EquivalenceTestRecord>> byPopulation;

    private EquivalenceTestGate(Map<String, List<EquivalenceTestRecord>> byPopulation) {
        this.byPopulation = byPopulation;
    }

    /**
     * A gate over the equivalence tests on file.
     *
     * <p>Several tests per population is the normal state after a few years of annual
     * re-performance, and the register keeps them all: the reporting date decides which one
     * governs, so discarding superseded records would make a DT-1 replay of a closed period
     * disagree with the original run.
     */
    public static EquivalenceTestGate of(Collection<EquivalenceTestRecord> records) {
        Objects.requireNonNull(records, "records");
        Map<String, List<EquivalenceTestRecord>> grouped = new LinkedHashMap<>();
        for (EquivalenceTestRecord record : records) {
            Objects.requireNonNull(record, "records must not contain null");
            grouped.computeIfAbsent(record.populationId(), key -> new ArrayList<>()).add(record);
        }
        // Both levels are copied immutably, and the inner one is the level that matters. A
        // register handing back the live list it groups by is a register any caller can rewrite:
        // clearing the list returned by recordsFor would turn a population evaluating
        // TIER_3_PERMITTED into NO_TEST_ON_FILE on the next call, which is a measurement basis
        // changing with nothing in the audit trail to say why.
        Map<String, List<EquivalenceTestRecord>> immutable = new LinkedHashMap<>();
        grouped.forEach((population, forPopulation) ->
            immutable.put(population, List.copyOf(forPopulation)));
        return new EquivalenceTestGate(Map.copyOf(immutable));
    }

    /** A gate with nothing on file — every Tier 3 proposal is demoted. */
    public static EquivalenceTestGate empty() {
        return new EquivalenceTestGate(Map.of());
    }

    /** Every test on file for a population, in the order supplied. */
    public List<EquivalenceTestRecord> recordsFor(String populationId) {
        Objects.requireNonNull(populationId, "populationId");
        return byPopulation.getOrDefault(populationId, List.of());
    }

    /**
     * The test that governs {@code populationId} as at {@code asOf}, or null where none does.
     *
     * <p>"Governs" means: the most recently performed test that had actually been performed by
     * the reporting date. Two rules, and both matter.
     *
     * <p>Tests dated after {@code asOf} are excluded rather than preferred. A test performed in
     * June says nothing about whether a March close was defensible, and admitting it would mean
     * a replay of March produced a Tier 3 measurement where the original run produced Tier 2 —
     * a DT-1 breach, and one that would look like a bug in the engine rather than in the
     * register. Excluding them also means a post-dated submission cannot hide an older test
     * that is still in date: the older one is still the one that governs.
     *
     * <p>Where two tests share a performance date, the <em>less favourable</em> one wins — the one
     * with less headroom against its own threshold, whether that headroom is negative (a breach) or
     * positive (a pass with little room). A duplicate submission is a data-quality matter this
     * register does not adjudicate, but it must not become a way to launder a test by re-recording
     * it on the same day with a better delta. Ranked on
     * {@link EquivalenceTestRecord#thresholdHeadroomBps()} and not on the excess over the
     * threshold, because the excess clamps at zero and so cannot separate two passing duplicates —
     * which left the answer to the register's list order.
     */
    public EquivalenceTestRecord governingTest(String populationId, LocalDate asOf) {
        Objects.requireNonNull(populationId, "populationId");
        Objects.requireNonNull(asOf, "asOf");
        EquivalenceTestRecord governing = null;
        for (EquivalenceTestRecord candidate : recordsFor(populationId)) {
            if (!candidate.wasPerformedBy(asOf)) {
                continue;
            }
            if (governing == null || supersedes(candidate, governing)) {
                governing = candidate;
            }
        }
        return governing;
    }

    private static boolean supersedes(
        EquivalenceTestRecord candidate, EquivalenceTestRecord incumbent) {
        if (candidate.performedOn().isAfter(incumbent.performedOn())) {
            return true;
        }
        if (candidate.performedOn().isBefore(incumbent.performedOn())) {
            return false;
        }
        // Less headroom wins, which is the javadoc's "less favourable" made total. This compared
        // excessOverThresholdBps() until a review found the excess clamps at zero: two same-day
        // duplicates that both passed reported nil excess each, compared equal, and the governing
        // test became whichever the register listed first -- so re-recording a test on the same day
        // with a better delta could take effect purely by landing later in the list, which is the
        // laundering route this tie-break exists to close. Headroom separates two passes as well as
        // two breaches.
        return candidate.thresholdHeadroomBps()
            .compareTo(incumbent.thresholdHeadroomBps()) < 0;
    }

    /**
     * Applies FR-411 and FR-412 to one proposed tier as at one reporting date.
     *
     * <p>Never throws for a data condition. A subject with no test on file, a stale test or a
     * forbidden return profile is a fact about the book, and the answer is a demotion recorded
     * with its reason.
     */
    public EquivalenceTestOutcome evaluate(EquivalenceTestSubject subject, LocalDate asOf) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(asOf, "asOf");

        // Anything other than a Tier 3 proposal is not this gate's business. TG-1 is a claim
        // about Tier 3 populations, so it passes vacuously rather than being absent: a control
        // report that simply omitted the invariant for non-Tier-3 populations could not be
        // distinguished from one where the gate had never run.
        if (subject.proposedTier() != MaterialityTier.TIER_3) {
            return outcome(subject, EquivalenceTestOutcome.Ground.NOT_TIER_3,
                subject.proposedTier(),
                "proposed " + subject.proposedTier()
                    + "; the Tier 3 equivalence test does not arise",
                BigDecimal.ZERO);
        }

        // FR-412, first and unconditionally. See the class comment: on a zero-coupon the
        // lifetime solved-versus-approximated delta is zero, so an equivalence test performed
        // as specified would pass on an instrument whose year-one error is 81.0% (Case 9).
        // Consulting the evidence before applying the refusal would therefore be worse than
        // useless — it would be evidence pointing the wrong way.
        if (subject.approximationForbidden()) {
            return outcome(subject, EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION,
                DEMOTION_TIER,
                "FR-412 refuses Tier 3 at any tenor — " + subject.forbiddenApproximationGround()
                    + "; original tenor " + subject.originalTenorMonths()
                    + " months is not a mitigant (03 § 10.3, Case 9: straight line overstates"
                    + " year-one income by 81.0% and understates the final year by 38.4% while"
                    + " the life totals tie at 684,758.30 exactly)",
                BigDecimal.ZERO);
        }

        EquivalenceTestRecord governing = governingTest(subject.populationId(), asOf);
        if (governing == null) {
            List<EquivalenceTestRecord> postdated = recordsFor(subject.populationId());
            if (!postdated.isEmpty()) {
                // Every test on file was performed after the reporting date. Reported
                // separately from "none at all" because the remediation differs: one is a
                // missing test, the other is a test attributed to the wrong period.
                EquivalenceTestRecord earliest = earliest(postdated);
                long daysAhead = ChronoUnit.DAYS.between(asOf, earliest.performedOn());
                return outcome(subject, EquivalenceTestOutcome.Ground.TEST_POSTDATED,
                    DEMOTION_TIER,
                    "the earliest equivalence test for this population was performed "
                        + earliest.performedOn() + ", " + daysAhead + " days after the reporting"
                        + " date " + asOf + "; a test cannot evidence a close struck before it",
                    BigDecimal.valueOf(-daysAhead));
            }
            return outcome(subject, EquivalenceTestOutcome.Ground.NO_TEST_ON_FILE, DEMOTION_TIER,
                "no equivalence test on file; 03 § 10.2 permits the Tier 3 shortcut only against"
                    + " a documented solved-versus-approximated comparison",
                BigDecimal.ZERO);
        }

        // Staleness before threshold, because staleness is the literal statement of TG-1 and
        // the delta of a stale test is not a figure to reason about in the first place. Both
        // can hold at once; the report names the one that has to be fixed first.
        if (!governing.isInDateOn(asOf)) {
            long daysOverdue = governing.daysOverdueOn(asOf);
            return outcome(subject, EquivalenceTestOutcome.Ground.TEST_STALE, DEMOTION_TIER,
                "equivalence test performed " + governing.performedOn() + " expired "
                    + governing.expiresOn() + ", " + daysOverdue + " days before the reporting"
                    + " date " + asOf + "; FR-411 demotes the population to "
                    + DEMOTION_TIER,
                BigDecimal.valueOf(daysOverdue));
        }

        if (!governing.isWithinThreshold()) {
            return outcome(subject, EquivalenceTestOutcome.Ground.TEST_OVER_THRESHOLD,
                DEMOTION_TIER,
                "equivalence test performed " + governing.performedOn() + " is in date to "
                    + governing.expiresOn() + " and failed: delta "
                    + governing.deltaBps().toPlainString() + " bps against a threshold of "
                    + governing.boardApprovedThresholdBps().toPlainString() + " bps approved by "
                    + governing.approvedBy() + " (03 § 10.2 item 2)",
                governing.excessOverThresholdBps());
        }

        return outcome(subject, EquivalenceTestOutcome.Ground.TIER_3_PERMITTED,
            MaterialityTier.TIER_3,
            "Tier 3 permitted against a current equivalence test — " + governing.describe(),
            BigDecimal.ZERO);
    }

    private static EquivalenceTestRecord earliest(List<EquivalenceTestRecord> records) {
        EquivalenceTestRecord earliest = records.get(0);
        for (EquivalenceTestRecord candidate : records) {
            if (candidate.performedOn().isBefore(earliest.performedOn())) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    /**
     * Assembles the outcome, and with it the TG-1 result.
     *
     * <p>The deviation carried by a breach is a signed day count where the breach is about
     * dates — positive for days beyond the annual window, negative for days a test was dated
     * into the future — and basis points where it is about the delta. Two units under one
     * invariant is a compromise, and the alternative was worse: a categorical breach reported
     * with no magnitude at all tells a control report nothing about whether a population is one
     * day late or three years late. The detail string always names the unit, so the figure is
     * never read bare.
     *
     * <p>The compromise stops here. {@link #tierGateInvariant} re-strikes the deviation as a
     * count of breaching populations, because a two-unit figure conjoined across a whole run
     * would depend on the order populations were gated in.
     */
    private static EquivalenceTestOutcome outcome(
        EquivalenceTestSubject subject,
        EquivalenceTestOutcome.Ground ground,
        MaterialityTier effectiveTier,
        String basis,
        BigDecimal deviation) {
        String detail = "TG-1 " + subject.populationId() + ": " + basis;
        InvariantResult result = ground.breachesTierGate()
            ? InvariantResult.fail(InvariantId.TG_1, detail, deviation)
            : InvariantResult.pass(InvariantId.TG_1, detail);
        ExceptionCategory exception =
            ground.raisesException() ? ExceptionCategory.STALE_EQUIVALENCE_TEST : null;
        return new EquivalenceTestOutcome(
            subject.populationId(), subject.proposedTier(), effectiveTier, ground, result,
            exception, basis);
    }

    /** Gates several subjects as at one reporting date, in the order supplied. */
    public List<EquivalenceTestOutcome> evaluateAll(
        List<EquivalenceTestSubject> subjects, LocalDate asOf) {
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(asOf, "asOf");
        List<EquivalenceTestOutcome> outcomes = new ArrayList<>(subjects.size());
        for (EquivalenceTestSubject subject : subjects) {
            outcomes.add(evaluate(subject, asOf));
        }
        return List.copyOf(outcomes);
    }

    /**
     * The one TG-1 result a run is entitled to, conjoined over every population gated.
     *
     * <p>TG-1 is asserted per population and reported once — 05 § 5 aggregates it in the
     * period-close batch alongside SL-1, PF-1 and HB-1. {@code InvariantResult.conjunction} is
     * what makes "once" mean the conjunction rather than whichever population happened to come
     * first: a report that named TG-1 satisfied while one population's test was three years
     * stale would be a control reporting green on a book that breached.
     *
     * <p>An empty run passes. No Tier 3 population presented is not a Tier 3 population without
     * evidence, and {@code conjunction} refuses an empty list precisely so that this decision
     * has to be made explicitly here rather than defaulted.
     *
     * <p><b>The aggregate deviation is a population count, not a day count or a spread.</b> The
     * per-population deviations are in two units — days for the date grounds, basis points for
     * a threshold breach — and {@code conjunction} keeps the first breach's figure, so the bare
     * number in an aggregated result would depend on the order populations happened to be gated
     * in and its unit would be unknowable from the value. A count of breaching populations is
     * one unit, order-independent, and the figure a period-close report actually wants next to
     * TG-1: how many populations are taking a shortcut nobody has evidence for. The individual
     * magnitudes stay readable on each {@link EquivalenceTestOutcome}, which is where they mean
     * something.
     */
    public static InvariantResult tierGateInvariant(List<EquivalenceTestOutcome> outcomes) {
        Objects.requireNonNull(outcomes, "outcomes");
        if (outcomes.isEmpty()) {
            return InvariantResult.pass(
                InvariantId.TG_1, "TG-1: no population presented to the tier gate");
        }
        List<InvariantResult> results = new ArrayList<>(outcomes.size());
        int breaching = 0;
        for (EquivalenceTestOutcome outcome : outcomes) {
            results.add(outcome.tierGateResult());
            if (!outcome.tierGateResult().satisfied()) {
                breaching++;
            }
        }
        // conjunction assembles the detail — every population's statement, deduplicated, in the
        // order gated — and settles whether the run holds. Only the deviation is re-struck.
        InvariantResult conjoined = InvariantResult.conjunction(results);
        if (conjoined.satisfied()) {
            return conjoined;
        }
        return InvariantResult.fail(
            InvariantId.TG_1,
            conjoined.detail() + " — " + breaching + " of " + outcomes.size()
                + " populations breached TG-1",
            BigDecimal.valueOf(breaching));
    }
}
