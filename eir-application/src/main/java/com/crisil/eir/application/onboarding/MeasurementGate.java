package com.crisil.eir.application.onboarding;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.util.Objects;

/**
 * The measurement-category and SPPI gate, which runs <b>first</b> — before any projection and
 * before any solve (FR-103, FR-104, 05 § 3.1).
 *
 * <h2>Why the ordering is the whole point of this class</h2>
 *
 * <p>05 § 3.1, in prose directly under the sequence diagram: "The SPPI/measurement gate runs
 * <b>first</b>, before any projection or solve. On the asset side an SPPI failure is a cliff, not a
 * gradient: the whole instrument goes to FVTPL and no EIR arises. Doing expensive work before that
 * check is waste, and worse, produces a rate for an instrument that should not have one."
 *
 * <p>The second half of that sentence is the one worth restating. Waste is a performance problem
 * and the engine has a performance budget it can afford to miss occasionally — 05 § 7 targets 10M
 * contracts in a four-hour window at roughly 700 contracts a second, and a gate that ran late
 * would cost a fraction of that. Producing a rate for an instrument that should not have one is not
 * a performance problem. It is a number in a field whose existence asserts amortised cost, sitting
 * beside numbers that are real, indistinguishable from them by inspection and reconciling perfectly
 * all the way to the general ledger. {@code OptionalitySppiFailureException} already makes the
 * argument one module down for the expected-life field: "any life returned here would be read as
 * evidence of amortised cost."
 *
 * <p>So the ordering is not a comment and not a convention. It is enforced by
 * {@link InitialRecognition}, which calls this before it touches a projector, and it is
 * <em>observable</em> from the output: {@link OnboardingOutcome#workPerformed()} records what work
 * was actually done, and ST-12's work leg fails on an outcome that shows a projection or a solve
 * for a contract with no EIR.
 *
 * <h2>The rules, and where each of them comes from</h2>
 *
 * <p>Every branch below is the Java form of a CHECK constraint in
 * {@code eir-persistence/src/main/resources/db/migration/V1__core_entities.sql}. The two boundaries
 * are deliberately made to say the same thing, because a gate the application enforces and the
 * schema does not is a gate a bulk load goes around, and a gate the schema enforces and the
 * application does not is a run that dies at the writer having done all the work.
 *
 * <table border="1">
 *   <caption>The gate's decision table</caption>
 *   <tr><th>Instrument class</th><th>SPPI on file</th><th>Declared category</th>
 *       <th>Outcome</th><th>Schema constraint</th></tr>
 *   <tr><td>LIABILITY</td><td>anything, including none</td><td>as declared</td>
 *       <td>as declared — the SPPI test does not apply (FR-105)</td>
 *       <td>both SPPI constraints exempt LIABILITY explicitly</td></tr>
 *   <tr><td>asset</td><td>FAIL</td><td>FVTPL</td>
 *       <td>FVTPL, excluded from EIR processing</td>
 *       <td>{@code contract_sppi_fail_implies_fvtpl_ck}</td></tr>
 *   <tr><td>asset</td><td>FAIL</td><td>AMORTISED_COST or FVOCI</td>
 *       <td>FVTPL anyway, and ST-12 breaches: the source system's category disagrees with its own
 *           assessment</td>
 *       <td>{@code contract_sppi_fail_implies_fvtpl_ck} would reject the row</td></tr>
 *   <tr><td>asset</td><td>PASS</td><td>AMORTISED_COST or FVOCI</td>
 *       <td>admitted — the EIR arises and the pipeline continues</td>
 *       <td>{@code contract_amortised_cost_needs_sppi_ck}</td></tr>
 *   <tr><td>asset</td><td>PASS</td><td>FVTPL</td>
 *       <td>FVTPL, excluded. A passing SPPI does not compel amortised cost: the business-model
 *           test is the other limb, and a held-for-trading bond passes SPPI and is still FVTPL</td>
 *       <td>FVTPL satisfies the first disjunct of both constraints</td></tr>
 *   <tr><td>asset</td><td>none</td><td>AMORTISED_COST or FVOCI</td>
 *       <td>refused: {@link ExceptionCategory#MISSING_MANDATORY_FIELD}</td>
 *       <td>{@code contract_amortised_cost_needs_sppi_ck}, and see the three-valued-logic note
 *           below</td></tr>
 *   <tr><td>asset</td><td>none</td><td>FVTPL</td>
 *       <td>FVTPL, excluded. Nothing needs assessing to keep an instrument out of the EIR
 *           regime</td>
 *       <td>{@code measurement_category = 'FVTPL'} satisfies both</td></tr>
 * </table>
 *
 * <h2>The three-valued-logic note, which is not a footnote</h2>
 *
 * <p>The migration carries this comment against {@code contract_amortised_cost_needs_sppi_ck}, and
 * it is the reason the missing-assessment row is refused here rather than waved through:
 *
 * <blockquote>IS NOT DISTINCT FROM rather than =, and that is the whole constraint. A CHECK is
 * satisfied when its expression is NULL, so {@code sppi_outcome = 'PASS'} accepts a row whose
 * sppi_outcome is NULL — the missing-assessment case this constraint exists for, waved through by
 * three-valued logic. <b>The first live run of this migration accepted exactly that row.</b>
 * </blockquote>
 *
 * <p>The Java analogue of that defect is {@code assessment.outcome() == PASS} on a null-checked
 * reference that silently short-circuits, or an {@code Optional.map(...).orElse(true)}. So the
 * absence of an assessment is tested first and named, and the outcome is only read once the
 * assessment is known to exist.
 *
 * <h2>Stateless, and no clock</h2>
 *
 * <p>The gate reads three attributes and decides. It holds no policy version, because the SPPI
 * test is a requirement rather than an election — there is no approved threshold to version — and
 * it reads no date, because nothing in the decision depends on one. The assessment's own date
 * travels on {@link SppiAssessment} as an input, per 03 § 1.1, and is never compared with "now".
 */
public final class MeasurementGate {

    private MeasurementGate() {
    }

    /**
     * Gates one contract.
     *
     * @param contractId       the contract, for the decision record and any queue entry
     * @param instrumentClass  which side of FR-104 the instrument sits on
     * @param declaredCategory the category the source system asserted — checked, never trusted
     * @param assessment       the SPPI assessment on file, or null where there is none
     */
    public static MeasurementDecision assess(
        String contractId,
        InstrumentClass instrumentClass,
        MeasurementCategory declaredCategory,
        SppiAssessment assessment) {

        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(instrumentClass, "instrumentClass");
        Objects.requireNonNull(declaredCategory, "declaredCategory");

        if (!instrumentClass.subjectToSppi()) {
            return liabilitySide(contractId, instrumentClass, declaredCategory, assessment);
        }
        if (assessment == null) {
            return assetSideWithNoAssessment(contractId, instrumentClass, declaredCategory);
        }
        if (assessment.outcome() == SppiOutcome.FAIL) {
            return assetSideSppiFailure(contractId, instrumentClass, declaredCategory, assessment);
        }
        return assetSideSppiPass(contractId, instrumentClass, declaredCategory, assessment);
    }

    /** Convenience over an assembled request — the form {@link InitialRecognition} calls. */
    public static MeasurementDecision assess(OnboardingRequest request) {
        Objects.requireNonNull(request, "request");
        return assess(request.contractId(), request.instrumentClass(),
            request.declaredCategory(), request.sppiAssessment());
    }

    /**
     * FR-105: the liability side is outside the SPPI test, so the declared category stands.
     *
     * <p>Not "SPPI is assumed to pass" and not "SPPI is skipped as a shortcut". The schema exempts
     * {@code LIABILITY} from both SPPI constraints and its own comment gives the reason — "A
     * liability is outside the SPPI test, so the liability side is exempted rather than forced" —
     * and FR-105 goes further, requiring an embedded derivative to be bifurcated to FVTPL while
     * <em>an EIR is retained on the host</em>. Running an asset-side gate over an issued structured
     * deposit would send the host to FVTPL and destroy a rate the requirement says must exist.
     *
     * <p>An assessment on file for a liability is not an error and is not discarded: it is recorded
     * on the decision and stated in the detail, because the schema's
     * {@code contract_sppi_complete_ck} permits the triple on any instrument class. It simply does
     * not decide anything here.
     */
    private static MeasurementDecision liabilitySide(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment assessment) {

        String detail = "instrument class " + instrumentClass + " is outside the SPPI test"
            + " (FR-104 confines it to the asset side; FR-105 bifurcates an embedded derivative to"
            + " FVTPL and retains an EIR on the host), so the declared category "
            + declaredCategory + " stands"
            + (assessment == null ? "" : " — an assessment is on file (" + assessment.describe()
                + ") and is recorded but decides nothing here");
        // ST-12 is not asserted. Its statement is "SPPI failure yields no EIR", and on the
        // liability side there is no SPPI test to fail — so the claim has no subject. A pass here
        // would be a result no input could turn into a breach, which is the defect family this
        // programme has repeatedly shipped; the absence is reported by
        // OnboardingRun.unassertedInvariants() instead.
        return MeasurementDecision.concluded(contractId, instrumentClass, declaredCategory,
            assessment, declaredCategory, detail, null);
    }

    /**
     * An asset claiming amortised cost or FVOCI with no SPPI assessment on file: refused.
     *
     * <p>This is the gate having been skipped, and FR-104 exists to make that visible. The category
     * is an assertion the source system made; the assessment is the evidence for it; and evidence
     * that does not exist cannot be replaced by a default in either direction. Defaulting to PASS
     * admits an unassessed instrument to the EIR regime — the exact row the migration's first live
     * run accepted. Defaulting to FAIL sends a perfectly ordinary term loan to FVTPL, which
     * misstates the book in the other direction and would be found late, because an FVTPL
     * instrument produces no EIR figures for anything to disagree with.
     *
     * <p>An asset declaring FVTPL with no assessment is <em>not</em> refused: nothing needs
     * assessing to keep an instrument out of the EIR regime, and the schema agrees — the first
     * disjunct of {@code contract_amortised_cost_needs_sppi_ck} is
     * {@code measurement_category = 'FVTPL'}.
     */
    private static MeasurementDecision assetSideWithNoAssessment(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory) {

        if (!declaredCategory.carriesEir()) {
            String detail = "no SPPI assessment on file, and none is required: the declared"
                + " category is " + declaredCategory + ", which carries no EIR. Recorded and"
                + " excluded from EIR processing (FR-103)";
            return MeasurementDecision.concluded(contractId, instrumentClass, declaredCategory,
                null, MeasurementCategory.FVTPL, detail, null);
        }
        String detail = "instrument class " + instrumentClass + " declares " + declaredCategory
            + " with no SPPI assessment on file. FR-104 makes the assessment the evidence for the"
            + " category, and both available defaults are wrong: PASS admits an unassessed"
            + " instrument to the EIR regime — the row the schema's"
            + " contract_amortised_cost_needs_sppi_ck was rewritten with IS NOT DISTINCT FROM to"
            + " catch, after the first live run accepted it — and FAIL sends an ordinary exposure"
            + " to FVTPL where no figures exist for anything to disagree with. Refused as "
            + ExceptionCategory.MISSING_MANDATORY_FIELD + ": the missing field is sppi_outcome";
        return MeasurementDecision.refused(contractId, instrumentClass, declaredCategory, null,
            ExceptionCategory.MISSING_MANDATORY_FIELD, detail);
    }

    /**
     * The cliff: an asset that fails SPPI goes to FVTPL whatever the feed declared.
     *
     * <p>The SPPI outcome is decisive and the declared category is the thing being checked, not the
     * thing being obeyed. So the effective category is FVTPL either way, and where the feed said
     * otherwise ST-12 breaches — a real finding, and a common one during a migration, because the
     * classification file and the contract master are maintained by different teams on different
     * cycles.
     *
     * <p><b>Not an exception.</b> The instrument is measured correctly, at fair value through profit
     * or loss, and is recorded and excluded from EIR processing. Nothing about it needs working in a
     * queue. What needs correcting is the source system's {@code measurement_category} column, and
     * an ST-12 breach on the run's invariant report is where a control owner looks for that; 03 § 9
     * blocks the close on it, which is a stronger consequence than a queue entry that can be
     * accepted with approval.
     */
    private static MeasurementDecision assetSideSppiFailure(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment assessment) {

        // ST-12, classification leg. WHAT INPUT MAKES THIS FAIL: an asset-side instrument whose
        // assessment reads FAIL while its contract master declares AMORTISED_COST or FVOCI — e.g.
        // (LOAN, SppiOutcome.FAIL, MeasurementCategory.AMORTISED_COST). The two operands come from
        // two independent sources, which is the only reason a comparison between them can
        // disagree: deriving the "expected" category from the assessment and then comparing it
        // with itself would be the derived-value-against-its-own-source trap CoreBankingFeed's
        // javadoc names.
        InvariantResult check;
        if (declaredCategory.carriesEir()) {
            check = InvariantResult.fail(InvariantId.ST_12,
                "contract " + contractId + " (" + instrumentClass + ") carries "
                    + assessment.describe() + " and its contract master declares "
                    + declaredCategory + ", which carries an EIR. FR-104: on the asset side an SPPI"
                    + " failure sends the whole instrument to FVTPL and there is no bifurcation."
                    + " The gate measures it at FVTPL regardless; the declared category is wrong"
                    + " and the schema's contract_sppi_fail_implies_fvtpl_ck would reject the row",
                MeasurementDecision.ONE_INSTRUMENT);
        } else {
            check = InvariantResult.pass(InvariantId.ST_12,
                "contract " + contractId + " (" + instrumentClass + ") carries "
                    + assessment.describe() + " and is declared " + declaredCategory
                    + "; the failure yields no EIR, as FR-104 requires");
        }
        String detail = "SPPI FAIL on the asset side is a cliff, not a gradient (05 § 3.1): the"
            + " whole instrument is measured at FVTPL and no EIR arises. " + assessment.describe()
            + ". Recorded and excluded from EIR processing (FR-103)"
            + (declaredCategory.carriesEir()
                ? ", and the declared category " + declaredCategory + " breaches "
                    + InvariantId.ST_12
                : "");
        return MeasurementDecision.concluded(contractId, instrumentClass, declaredCategory,
            assessment, MeasurementCategory.FVTPL, detail, check);
    }

    /**
     * SPPI passes: the declared category stands, and it may still be FVTPL.
     *
     * <p>A passing SPPI test is necessary for amortised cost and FVOCI and is not sufficient for
     * either. The business-model test is the other limb, and a bond that passes SPPI and is held
     * for trading is FVTPL — which is why this branch honours the declared category rather than
     * promoting everything that passes into the EIR regime. Promoting would be the mirror-image
     * defect of the one this whole class exists to prevent: an instrument that should be
     * remeasured to fair value quietly accreting at a rate.
     */
    private static MeasurementDecision assetSideSppiPass(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment assessment) {

        // ST-12, classification leg, on the passing side. Satisfied here by construction of this
        // branch — there is no failure to yield anything — but it is the SAME result type published
        // by the failing branch above, and the population aggregate reads both. It is published
        // rather than omitted because ST-12's denominator has to be the contracts the gate actually
        // examined: an aggregate whose denominator is only the breaching cases cannot distinguish
        // "no breaches" from "nothing looked at", and that distinction is what
        // OnboardingRun.unassertedInvariants() exists to keep.
        InvariantResult check = InvariantResult.pass(InvariantId.ST_12,
            "contract " + contractId + " (" + instrumentClass + ") carries "
                + assessment.describe() + "; no SPPI failure, so " + InvariantId.ST_12
                + " has nothing to exclude and the declared category " + declaredCategory
                + " stands");
        String detail = declaredCategory.carriesEir()
            ? "SPPI PASS and declared " + declaredCategory + ": the EIR arises and the pipeline"
                + " continues to fee classification (05 § 3.1). " + assessment.describe()
            : "SPPI PASS and declared " + declaredCategory + ": a passing assessment is necessary"
                + " for amortised cost and not sufficient — the business-model test is the other"
                + " limb, and a held-for-trading holding passes SPPI and is still FVTPL. Recorded"
                + " and excluded from EIR processing (FR-103). " + assessment.describe();
        return MeasurementDecision.concluded(contractId, instrumentClass, declaredCategory,
            assessment, declaredCategory, detail, check);
    }
}
