package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.api.http.Json;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.pool.PoolDefinition;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestOutcome;
import com.crisil.eir.policy.tier.EquivalenceTestRecord;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * FR-809's register: the incidence of every shortcut in force, or a named gap where the engine
 * cannot say.
 *
 * <p><b>What this class is for, in 06 § 7's own words.</b> "{@code /reports/approximations}
 * deserves note. It reports Tier 3 populations with equivalence-test dates, ACPIR 51 contractual
 * fallbacks with justifications, pool-level measurement with back-test variances, and revolving
 * approximations. Making the shortcuts <i>visible</i> is what keeps them defensible — an
 * undocumented approximation drifting quietly across a portfolio is the failure this endpoint
 * exists to prevent."
 *
 * <h2>The one design decision, and it is the whole class</h2>
 *
 * <p>A report on approximations has a failure mode no figure-bearing report has: it can be
 * <em>wrong by being empty</em>. A movement schedule whose columns do not sum announces itself. A
 * register that omits a category renders as {@code "rows": []}, reads as "none in force", and is
 * indistinguishable from coverage. Which means the report is then worse than no report at all,
 * because a reader who has one stops looking.
 *
 * <p>So this register never returns an empty list to mean two different things.
 * {@link ApproximationSources} answers each category with either what a source holds or the reason
 * there is no source, {@link CategoryReturn.Status} carries three values rather than two, and
 * {@link CategoryReturn}'s constructor refuses a gap that cannot say what is missing. The report
 * publishes {@code complete} — false while any category is {@link CategoryReturn.Status#NOT_AVAILABLE}
 * — and enumerates the gaps at the top level, so an incomplete register cannot be mistaken for a
 * clean one by a reader who scrolled past the categories.
 *
 * <p>The structural half is {@link ApproximationCategory}: the loop is over {@code values()}, so a
 * category cannot be omitted by an author forgetting to render it. Omitting one requires deleting
 * it from an enum, where it is visible in a diff.
 *
 * <h2>What it computes rather than restates</h2>
 *
 * <p>Nothing about a shortcut's permissibility is decided here. Tier 3 goes to
 * {@link EquivalenceTestGate}, which applies FR-412's absolute refusal before consulting any
 * evidence and FR-411's annual window after it; the aggregate TG-1 result is
 * {@link EquivalenceTestGate#tierGateInvariant}. Pool eligibility is
 * {@code SuspensionPools.eligibility}, which is PL-2. The ACPIR 51 fallback is read off
 * {@code ContractTerms} rather than asserted. This class assembles and renders; the policy lives
 * where it lives, and a report that re-derived any of it would eventually publish a different
 * answer from the one the engine measured on.
 */
public final class ApproximationRegister {

    private final ApproximationSources sources;

    public ApproximationRegister(ApproximationSources sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /**
     * The assembled register: every category, the parameters governing it, and the completeness
     * arithmetic.
     *
     * <p>A value rather than only JSON, because the Ind AS 107 extract of FR-806 has to state
     * whether the approximations disclosure it carries is complete, and it must read that from the
     * same assembly the {@code /reports/approximations} endpoint published rather than recompute
     * it. Two answers to "is the register complete" is how a disclosure signs off over a gap.
     *
     * @param periodId   the reporting period, {@code yyyyMM}
     * @param asOf       the reporting date the shortcuts are measured at
     * @param categories one block per {@link ApproximationCategory}, in declaration order
     * @param parameters the thresholds and rules deciding what this register catches
     */
    public record Assembled(
        int periodId,
        LocalDate asOf,
        List<CategoryReturn> categories,
        List<PolicyParameter> parameters) {

        public Assembled {
            Objects.requireNonNull(asOf, "asOf");
            categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            // The structural guarantee, asserted rather than trusted. If this ever fails, a
            // category was rendered twice or not at all, and the report would be claiming
            // coverage of FR-809's four while carrying three.
            if (categories.size() != ApproximationCategory.values().length) {
                throw new IllegalArgumentException(
                    "the register carries " + categories.size() + " categories and FR-809 names "
                        + ApproximationCategory.values().length
                        + "; a register missing a category reads as coverage of it");
            }
            Set<ApproximationCategory> seen = new LinkedHashSet<>();
            for (CategoryReturn block : categories) {
                if (!seen.add(block.category())) {
                    throw new IllegalArgumentException(
                        "category " + block.category() + " is reported twice");
                }
            }
        }

        /** Whether every category had a source. False while any category is a gap. */
        public boolean complete() {
            return categories.stream().noneMatch(CategoryReturn::isGap);
        }

        /** The gaps, one sentence each, for the top level of the response. */
        public List<String> gaps() {
            List<String> gaps = new ArrayList<>();
            for (CategoryReturn block : categories) {
                if (block.isGap()) {
                    gaps.add(block.category().name() + " (" + block.category().title() + "): "
                        + block.gap());
                }
            }
            return List.copyOf(gaps);
        }

        /** Shortcuts actually applied in the period, across every category with a source. */
        public long shortcutsInForce() {
            return categories.stream().mapToLong(CategoryReturn::inForceCount).sum();
        }

        /**
         * Shortcuts applied with nothing on file to defend them.
         *
         * <p>The single figure this endpoint exists to publish. Read it only together with
         * {@link #complete()}: a zero on an incomplete register means "none found in the
         * categories that had a source", which is a much weaker statement than it looks.
         */
        public long undocumentedShortcuts() {
            return categories.stream().mapToLong(CategoryReturn::undocumentedCount).sum();
        }

        /** Parameters nobody has approved, which a reader must weigh differently. */
        public List<PolicyParameter> parametersRequiringBoardAttention() {
            return parameters.stream().filter(PolicyParameter::requiresBoardAttention).toList();
        }
    }

    /**
     * The reporting date a {@code yyyyMM} period ends on.
     *
     * <p>Month end rather than the first of the month, because every window in this register is
     * measured against a reporting date and 03 § 10.2's annual test and 03 § 10.1's quarterly
     * back-test are both struck at period ends. Taking the first would report a test as current
     * for a month after it expired.
     *
     * @throws IllegalArgumentException where the period is not a {@code yyyyMM}
     */
    public static LocalDate periodEnd(int periodId) {
        int year = periodId / 100;
        int month = periodId % 100;
        if (year < 1900 || year > 9999 || month < 1 || month > 12) {
            throw new IllegalArgumentException(
                "period '" + periodId + "' is not a yyyyMM reporting period; the register's"
                    + " windows are measured against a reporting date, and there is no date to"
                    + " measure against here");
        }
        return YearMonth.of(year, month).atEndOfMonth();
    }

    /** Assembles the register for one period. Never throws for a data condition. */
    public Assembled assemble(int periodId) {
        LocalDate asOf = periodEnd(periodId);
        List<CategoryReturn> categories = new ArrayList<>();
        // Over values(), not over four calls. See the class javadoc: this is what makes a dropped
        // category a deletion from an enum rather than a forgotten line in a render method.
        for (ApproximationCategory category : ApproximationCategory.values()) {
            categories.add(switch (category) {
                case TIER_3_APPROXIMATION -> tier3(periodId, asOf);
                case CONTRACTUAL_LIFE_FALLBACK -> contractualLifeFallbacks(periodId);
                case POOL_LEVEL_MEASUREMENT -> pools(periodId, asOf);
                case REVOLVING_APPROXIMATION -> revolvingElections(periodId);
            });
        }
        return new Assembled(periodId, asOf, categories, PolicyParameter.governing());
    }

    // ------------------------------------------------------------------ the four categories

    /**
     * Tier 3 populations, gated through {@link EquivalenceTestGate}.
     *
     * <p>Built against the gate directly and not against whatever records a tier assignment
     * happens to leave behind, because the gate is where FR-411 and FR-412 live and a report that
     * re-implemented either would eventually disagree with the measurement. In particular the
     * ordering is the gate's: FR-412's refusal of zero-coupon and deep-discount instruments is
     * applied <em>before</em> any evidence is consulted and cannot be cured by evidence, because
     * on a zero-coupon the lifetime solved-versus-approximated delta is zero and a test performed
     * as 03 § 10.2 specifies would <em>pass</em> on an instrument whose year-one error is 81.0%
     * (reference case 9). A register that consulted the evidence first would publish that pass.
     */
    private CategoryReturn tier3(int periodId, LocalDate asOf) {
        ApproximationSources.Answer<ApproximationSources.Tier3Submission> answer =
            sources.tier3(periodId);
        if (!answer.isAvailable()) {
            return CategoryReturn.notAvailable(
                ApproximationCategory.TIER_3_APPROXIMATION, answer.unavailable().reason());
        }
        ApproximationSources.Tier3Submission submission = answer.value();
        EquivalenceTestGate gate = submission.tests();
        List<EquivalenceTestOutcome> outcomes = gate.evaluateAll(submission.populations(), asOf);
        List<ApproximationRow> rows = new ArrayList<>(outcomes.size());
        for (int index = 0; index < outcomes.size(); index++) {
            EquivalenceTestSubject subject = submission.populations().get(index);
            EquivalenceTestOutcome outcome = outcomes.get(index);
            EquivalenceTestRecord governing = gate.governingTest(subject.populationId(), asOf);
            // Evidenced is TIER_3_PERMITTED and nothing else. An in-date test whose delta breached
            // the Board threshold is evidence, and it is evidence that the shortcut does not hold
            // — counting it as evidence would turn a failed test into a permission.
            boolean permitted =
                outcome.ground() == EquivalenceTestOutcome.Ground.TIER_3_PERMITTED;
            rows.add(new ApproximationRow(
                ApproximationCategory.TIER_3_APPROXIMATION,
                subject.populationId(),
                ApproximationCategory.TIER_3_APPROXIMATION.shortcut()
                    + "; original tenor " + subject.originalTenorMonths() + " months, accretion"
                    + " share of total return "
                    + subject.accretionShareOfReturn().setScale(4, Precision.MODE)
                        .toPlainString(),
                outcome.tier3Permitted(),
                permitted,
                outcome.basis() + " — measured at " + outcome.effectiveTier(),
                governing == null ? null : governing.performedOn(),
                governing == null ? null : governing.expiresOn(),
                governing == null ? null : governing.deltaBps(),
                governing == null ? null : governing.boardApprovedThresholdBps(),
                "TG-1",
                outcome.exception() == null ? null : outcome.exception().name()));
        }
        // The one TG-1 result a period is entitled to, conjoined over every population gated. An
        // empty run passes, and the gate's own javadoc explains why that decision is made there
        // rather than defaulted here.
        InvariantResult tierGate = EquivalenceTestGate.tierGateInvariant(outcomes);
        List<String> notes = tier3Notes(submission);
        return rows.isEmpty()
            ? CategoryReturn.noneInForce(
                ApproximationCategory.TIER_3_APPROXIMATION, tierGate, notes)
            : CategoryReturn.reported(
                ApproximationCategory.TIER_3_APPROXIMATION, rows, tierGate, notes);
    }

    /**
     * The two things about this category that are not rows.
     *
     * <p>The first is FR-412, restated with reference case 9's figures attached. The rows already
     * carry the gate's own refusal sentence per population, but the refusal is the one conclusion
     * in this whole register that no evidence may reverse, and a reader skimming rows for
     * {@code evidenced: false} would otherwise treat it as a missing document rather than a
     * prohibition.
     *
     * <p>The second is a limitation of this register, published rather than left implicit.
     * {@link EquivalenceTestGate} exposes {@code recordsFor(populationId)} and no way to
     * enumerate the population ids it holds, so a test on file under an id nobody presented
     * cannot be detected here. That is the dangerous direction of the join-key failure
     * {@code EquivalenceTestRecord} strips whitespace to avoid: the population presents, no test
     * matches the id it was given, and a properly evidenced Tier 3 population is published as
     * {@code NO_TEST_ON_FILE} while its test sits in the register under a neighbouring key. The
     * pool category <em>can</em> detect its orphans and does; this one cannot, and says so.
     */
    private static List<String> tier3Notes(ApproximationSources.Tier3Submission submission) {
        List<String> notes = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        for (EquivalenceTestSubject subject : submission.populations()) {
            if (subject.approximationForbidden()) {
                forbidden.add(subject.populationId() + " ("
                    + subject.forbiddenApproximationGround() + ")");
            }
        }
        if (!forbidden.isEmpty()) {
            notes.add(forbidden.size() + " population(s) are refused Tier 3 outright by FR-412"
                + " and 03 § 10.3, at any tenor, and no equivalence test can reverse it: "
                + forbidden + ". On such an instrument the lifetime solved-versus-approximated"
                + " delta is zero, so a test performed exactly as 03 § 10.2 specifies would PASS."
                + " Reference case 9 measures the real error at 81.0% overstatement of year-one"
                + " income and 38.4% understatement of the final year, on two columns that both"
                + " total 684758.30 exactly — the tie is what makes the error invisible over the"
                + " life and material in every single period");
        }
        notes.add("this register cannot detect an equivalence test held for a population nobody"
            + " presented: EquivalenceTestGate exposes recordsFor(populationId) and no way to"
            + " enumerate the ids it holds. A population presented under a slightly different id"
            + " therefore reports NO_TEST_ON_FILE while its test sits in the register unmatched,"
            + " and that mismatch is invisible from this endpoint");
        return List.copyOf(notes);
    }

    /** The ACPIR 51 contractual fallbacks, and whether each is a justified election. */
    private CategoryReturn contractualLifeFallbacks(int periodId) {
        ApproximationSources.Answer<List<ContractualLifeFallback>> answer =
            sources.contractualLifeFallbacks(periodId);
        if (!answer.isAvailable()) {
            return CategoryReturn.notAvailable(
                ApproximationCategory.CONTRACTUAL_LIFE_FALLBACK, answer.unavailable().reason());
        }
        List<ApproximationRow> rows = new ArrayList<>();
        for (ContractualLifeFallback fallback : answer.value()) {
            rows.add(new ApproximationRow(
                ApproximationCategory.CONTRACTUAL_LIFE_FALLBACK,
                fallback.contractId(),
                ApproximationCategory.CONTRACTUAL_LIFE_FALLBACK.shortcut()
                    + ", being " + fallback.contractualTermMonths() + " months",
                true,
                fallback.isJustified(),
                fallback.describe(),
                fallback.electedOn(),
                null,
                null,
                null,
                // No invariant id covers an unjustified ACPIR 51 election. Reported as null
                // rather than borrowed from a neighbouring control: a row shown under TG-1 or
                // PL-2 would read as gated by a check that has never looked at it.
                null,
                null));
        }
        List<String> notes = List.of(
            "the fallback itself is detected from ContractTerms, whose expectedLifePeriods()"
                + " resolves an unstated eirExpectedLifeMonths to the contractual term (FR-310)."
                + " That method cannot tell an election from an omission, which is why the"
                + " justification is a separate field here and why a blank one is a finding"
                + " rather than a formatting problem",
            "no InvariantId covers an unjustified ACPIR 51 election, so this category carries no"
                + " invariant result and its rows cannot block a period close on that ground"
                + " alone");
        return rows.isEmpty()
            ? CategoryReturn.noneInForce(
                ApproximationCategory.CONTRACTUAL_LIFE_FALLBACK, null, notes)
            : CategoryReturn.reported(
                ApproximationCategory.CONTRACTUAL_LIFE_FALLBACK, rows, null, notes);
    }

    /** Pool-level measurement in force, each against 03 § 10.1's quarterly back-test. */
    private CategoryReturn pools(int periodId, LocalDate asOf) {
        ApproximationSources.Answer<ApproximationSources.PoolSubmission> answer =
            sources.pools(periodId);
        if (!answer.isAvailable()) {
            return CategoryReturn.notAvailable(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT, answer.unavailable().reason());
        }
        ApproximationSources.PoolSubmission submission = answer.value();
        List<PoolDefinition> inForce = submission.pools().inForceOn(asOf);
        List<ApproximationRow> rows = new ArrayList<>(inForce.size());
        Set<String> matched = new LinkedHashSet<>();
        for (PoolDefinition pool : inForce) {
            PoolBackTest governing = governingBackTest(submission.backTests(), pool.poolId(), asOf);
            if (governing != null) {
                matched.add(pool.poolId());
            }
            boolean defended = governing != null && governing.defendsPoolMeasurementOn(asOf);
            rows.add(new ApproximationRow(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT,
                pool.poolId(),
                ApproximationCategory.POOL_LEVEL_MEASUREMENT.shortcut() + ", measured at "
                    + submission.pools().measurementTierOfPooledExposures() + " over "
                    + pool.memberExposureIds().size() + " members",
                true,
                defended,
                poolBasis(pool, governing, asOf),
                governing == null ? null : governing.performedOn(),
                governing == null ? null : governing.expiresOn(),
                governing == null ? null : governing.varianceBps(),
                governing == null ? null : governing.thresholdBps(),
                "PL-2",
                defended ? null : ExceptionCategory.POOL_BACKTEST_BREACH.name()));
        }
        List<String> notes = new ArrayList<>();
        for (PoolBackTest orphan : submission.backTests()) {
            if (!matched.contains(orphan.poolId())) {
                notes.add("back-test on file for pool '" + orphan.poolId() + "' performed "
                    + orphan.performedOn() + " matches no pool in force on " + asOf
                    + "; either the pool id does not join — which would publish a properly"
                    + " back-tested pool as unevidenced — or the pool was dissolved and the"
                    + " evidence outlived it");
            }
        }
        InvariantResult eligibility = submission.pools().eligibility(asOf);
        return rows.isEmpty()
            ? CategoryReturn.noneInForce(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT, eligibility, notes)
            : CategoryReturn.reported(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT, rows, eligibility, notes);
    }

    private static String poolBasis(
        PoolDefinition pool, PoolBackTest governing, LocalDate asOf) {
        StringBuilder basis = new StringBuilder(pool.describe())
            .append("; eligibility ").append(pool.eligibility().satisfied() ? "PL-2 PASS" : "PL-2 FAIL")
            .append(" — ").append(pool.eligibility().detail());
        if (governing == null) {
            return basis.append("; NO quarterly back-test on file as at ").append(asOf)
                .append(" — 03 § 10.1 makes it mandatory, so this pool is measured collectively"
                    + " on no evidence at all").toString();
        }
        basis.append("; ").append(governing.describe());
        if (!governing.isInDateOn(asOf)) {
            basis.append("; STALE by ").append(governing.daysOverdueOn(asOf))
                .append(" days as at ").append(asOf);
        }
        if (!governing.isWithinThreshold()) {
            basis.append("; BREACHED its threshold by ")
                .append(governing.excessOverThresholdBps().toPlainString())
                .append(" bps, which 03 § 10.1 says forces the pool to contract-level"
                    + " measurement");
        }
        return basis.toString();
    }

    /**
     * The back-test that governs a pool as at {@code asOf}.
     *
     * <p>The same rule {@code EquivalenceTestGate.governingTest} applies, and for the same two
     * reasons. Tests performed after the reporting date are excluded rather than preferred: a
     * back-test struck in June says nothing about whether March's collective measurement was
     * defensible, and admitting it would make a DT-1 replay of March disagree with the original
     * run. Where two share a performance date the <em>less favourable</em> one wins, so a
     * duplicate submission cannot launder a breached back-test by re-recording it the same day
     * with a better variance.
     */
    private static PoolBackTest governingBackTest(
        List<PoolBackTest> backTests, String poolId, LocalDate asOf) {
        PoolBackTest governing = null;
        for (PoolBackTest candidate : backTests) {
            if (!candidate.poolId().equals(poolId) || !candidate.wasPerformedBy(asOf)) {
                continue;
            }
            if (governing == null || supersedes(candidate, governing)) {
                governing = candidate;
            }
        }
        return governing;
    }

    private static boolean supersedes(PoolBackTest candidate, PoolBackTest incumbent) {
        if (candidate.performedOn().isAfter(incumbent.performedOn())) {
            return true;
        }
        if (candidate.performedOn().isBefore(incumbent.performedOn())) {
            return false;
        }
        return candidate.excessOverThresholdBps()
            .compareTo(incumbent.excessOverThresholdBps()) > 0;
    }

    /** The ACPIR 54 revolving elections, and whether this engine can honour each. */
    private CategoryReturn revolvingElections(int periodId) {
        ApproximationSources.Answer<List<RevolvingElection>> answer =
            sources.revolvingElections(periodId);
        if (!answer.isAvailable()) {
            return CategoryReturn.notAvailable(
                ApproximationCategory.REVOLVING_APPROXIMATION, answer.unavailable().reason());
        }
        List<ApproximationRow> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (RevolvingElection election : answer.value()) {
            // An election this engine cannot apply is still an approximation in force: the
            // facility is measured on something, and ProjectorRegistry's RevolvingProjector
            // applies FEE_OVER_RENEWAL regardless. So inForce stays true and the row is
            // unevidenced, which is the honest reading — the book is being measured on an
            // approximation nobody elected.
            boolean evidenced = election.evidenced() && election.honourable();
            rows.add(new ApproximationRow(
                ApproximationCategory.REVOLVING_APPROXIMATION,
                election.productCode(),
                ApproximationCategory.REVOLVING_APPROXIMATION.shortcut() + ": "
                    + election.approximation(),
                true,
                evidenced,
                election.describe(),
                election.assessedOn(),
                null,
                null,
                null,
                null,
                null));
            if (!election.honourable()) {
                notes.add("product " + election.productCode() + " elects "
                    + election.approximation() + ", which RevolvingProjector refuses in its"
                    + " constructor; the facility is nevertheless projected, on"
                    + " FEE_OVER_RENEWAL, so it is measured on an approximation the product"
                    + " assessment did not choose");
            }
        }
        return rows.isEmpty()
            ? CategoryReturn.noneInForce(
                ApproximationCategory.REVOLVING_APPROXIMATION, null, notes)
            : CategoryReturn.reported(
                ApproximationCategory.REVOLVING_APPROXIMATION, rows, null, notes);
    }

    // ------------------------------------------------------------------------------ rendering

    /** The {@code GET /api/reports/approximations} body. */
    public Json.Obj render(Assembled assembled) {
        Objects.requireNonNull(assembled, "assembled");
        List<Json.Obj> categories = new ArrayList<>();
        int gaps = 0;
        for (CategoryReturn block : assembled.categories()) {
            if (block.isGap()) {
                gaps++;
            }
            categories.add(renderCategory(block));
        }
        List<Json.Obj> parameters = new ArrayList<>();
        for (PolicyParameter parameter : assembled.parameters()) {
            parameters.add(Json.object()
                .str("name", parameter.name())
                .str("value", parameter.value())
                .str("provenance", parameter.provenance().name())
                .str("provenanceMeaning", parameter.provenance().meaning())
                .str("reference", parameter.reference())
                .bool("requiresBoardAttention", parameter.requiresBoardAttention())
                .str("note", parameter.note()));
        }
        return Json.object()
            .str("report", "approximations")
            .str("requirement", "FR-809")
            .str("specSection", "06 § 7")
            .str("period", Integer.toString(assembled.periodId()))
            .str("asOf", assembled.asOf().toString())
            .bool("complete", assembled.complete())
            .count("categoriesReported", assembled.categories().size())
            .count("categoriesInFr809", ApproximationCategory.values().length)
            .count("categoriesNotAvailable", gaps)
            .count("shortcutsInForce", (int) assembled.shortcutsInForce())
            .count("undocumentedShortcuts", (int) assembled.undocumentedShortcuts())
            .count("parametersRequiringBoardAttention",
                assembled.parametersRequiringBoardAttention().size())
            .strings("gaps", assembled.gaps())
            .array("categories", categories)
            .array("policyParameters", parameters)
            .str("readingNote", readingNote(assembled));
    }

    private static Json.Obj renderCategory(CategoryReturn block) {
        List<Json.Obj> rows = new ArrayList<>(block.rows().size());
        for (ApproximationRow row : block.rows()) {
            rows.add(Json.object()
                .str("subject", row.subjectId())
                .str("shortcut", row.shortcut())
                .bool("inForce", row.inForce())
                .bool("evidenced", row.evidenced())
                .bool("undocumented", row.undocumented())
                .str("evidenceDate", row.evidenceDate() == null
                    ? null : row.evidenceDate().toString())
                .str("evidenceExpires", row.evidenceExpires() == null
                    ? null : row.evidenceExpires().toString())
                .figure("varianceBps", row.varianceBps())
                .figure("thresholdBps", row.thresholdBps())
                .str("invariant", row.invariant())
                .str("exception", row.exception())
                .str("basis", row.basis()));
        }
        Json.Obj rendered = Json.object()
            .str("category", block.category().name())
            .str("title", block.category().title())
            .str("shortcut", block.category().shortcut())
            .str("specReference", block.category().specReference())
            .str("invariantId", block.category().invariant())
            .str("status", block.status().name())
            .str("statusMeaning", block.status().meaning())
            .count("subjects", block.rows().size())
            .count("inForce", (int) block.inForceCount())
            .count("undocumented", (int) block.undocumentedCount())
            .str("gap", block.gap())
            .str("wouldBePopulatedBy", block.category().wouldBePopulatedBy())
            .array("rows", rows)
            .strings("notes", block.notes());
        if (block.invariant() == null) {
            return rendered.str("invariantResult", null);
        }
        return rendered.obj("invariantResult", Json.object()
            .str("id", block.invariant().id().name().replace('_', '-'))
            .str("statement", block.invariant().id().statement())
            .bool("satisfied", block.invariant().satisfied())
            .figure("deviation", block.invariant().deviation())
            .str("detail", block.invariant().detail()));
    }

    /**
     * The sentence that stops the report being misread.
     *
     * <p>On the response rather than only in this javadoc, because the reader of a JSON body is
     * not reading this file. It is the one piece of prose the endpoint cannot do without: every
     * count in the report is conditional on {@code complete}, and a reader who takes
     * {@code undocumentedShortcuts: 0} from an incomplete register has been misled by a true
     * figure.
     */
    private static String readingNote(Assembled assembled) {
        String base = "A category with status NOT_AVAILABLE is a GAP: the engine holds no source"
            + " for it, and this register therefore asserts nothing about whether that shortcut"
            + " is in force. NONE_IN_FORCE is the positive claim that it is not. The two are"
            + " deliberately different statuses because rendering both as an empty list of rows"
            + " is what lets an omission read as coverage — 06 § 7's stated reason for this"
            + " endpoint's existence.";
        if (assembled.complete()) {
            return base + " This register is complete: every one of FR-809's four categories had"
                + " a source, so undocumentedShortcuts is a figure over the whole population of"
                + " shortcuts in force.";
        }
        return base + " This register is INCOMPLETE — " + assembled.gaps().size() + " of "
            + assembled.categories().size() + " categories had no source. undocumentedShortcuts"
            + " counts only the categories that did, so a zero here is not a statement that no"
            + " undocumented approximation is in force across the portfolio. Read the gaps"
            + " first.";
    }
}
