package com.crisil.eir.policy.fee;

import com.crisil.eir.domain.FeeClassification;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The ACPIR 53 cost-function taxonomy: what function a cost paid by the bank served, and
 * therefore whether it capitalises into the gross carrying amount or is expensed (FR-203).
 *
 * <p><b>What this type is for.</b> Today the vocabulary exists as four loose strings —
 * {@code FeePosting.COST_FUNCTIONS} is {@code List.of("SELLING", "PROCESSING", "ADMIN",
 * "OTHER")}, upper-cased and otherwise untyped — and the ACPIR 53 capitalisability decision
 * exists nowhere: each call site that needs it re-derives it from the string, or assumes it.
 * Two call sites that assume differently is not a hypothetical, it is the normal fate of a
 * vocabulary carried as text. This enum states the decision once, per function, with the
 * paragraph it comes from attached, so the selling-versus-appraisal boundary is expressed in
 * one place and can be read rather than recalled.
 *
 * <p><b>The boundary itself.</b> ACPIR 53 includes "fees and commission paid to agents
 * (<b>including employees acting as selling agents</b>), advisers, brokers and dealers" and
 * excludes "debt premiums or discounts, financing costs, and internal administrative or holding
 * costs". The clause in bold is doing real work in an Indian bank: an incentive paid to branch
 * staff for <em>sourcing</em> a loan is a capitalisable transaction cost, while the salary of
 * the credit-appraisal team <em>assessing</em> that same loan is internal administrative cost
 * and is excluded. The line is drawn at selling, not at processing (01 § "Three India-specific
 * points"; 03 § 3.2). Source HR and cost-centre data is structured along neither line, which
 * is why 08 § 0 names this the single hardest data problem in the programme and why the
 * attribute is mandatory rather than defaulted.
 *
 * <h2>Finding: four cost functions cannot express the boundary FR-203 demands</h2>
 *
 * <p>This is reported rather than papered over, because the tidy enum would be the more
 * dangerous artefact. Mapping the four strings onto ACPIR 53:
 *
 * <table border="1">
 *   <caption>The four recorded functions against paragraph 53's two limbs</caption>
 *   <tr><th>Recorded function</th><th>ACPIR 53</th><th>Why</th></tr>
 *   <tr><td>{@code SELLING}</td><td>Capitalise</td>
 *       <td>Squarely the positive limb — DSA commission, and the employee selling-agent
 *           incentive paragraph 53 names.</td></tr>
 *   <tr><td>{@code ADMIN}</td><td>Exclude</td>
 *       <td>Squarely the negative limb — internal administrative cost.</td></tr>
 *   <tr><td>{@code PROCESSING}</td><td><b>Neither</b></td>
 *       <td>The ambiguous one. It covers internal credit appraisal, which paragraph 53
 *           excludes by name, <em>and</em> the external valuation, legal search, or credit-bureau
 *           charge paid to a third party in the course of origination, which is a directly
 *           attributable transaction cost that capitalises. One string, two answers, opposite
 *           signs on the carrying amount.</td></tr>
 *   <tr><td>{@code OTHER}</td><td><b>Neither</b></td>
 *       <td>A residual bucket. It carries no accounting information at all, and in practice it
 *           is where a feed puts everything it could not attribute.</td></tr>
 * </table>
 *
 * <p>So the vocabulary decides half the question. {@code PROCESSING} is precisely the word that
 * 01 § and 03 § 3.2 both use for the <em>excluded</em> side ("the dividing line is selling, not
 * processing"), which invites the reading that {@code PROCESSING} means appraisal and excludes
 * — while a bank's fee master routinely files an external valuer's invoice under processing,
 * where exclusion is wrong. Neither reading can be adopted in code without deciding an
 * accounting question that belongs to the fee master and its Board committee (ACPIR 57; the
 * "sourcing versus processing cost" item on that committee's agenda). Hence
 * {@link CostFunctionCapitalisability#INDETERMINATE} and hence
 * {@link CostFunctionResolution.Cause#INDETERMINATE}: the engine refuses the posting instead of
 * choosing.
 *
 * <p><b>What closing the gap would take</b> — recorded here because it is the requirement, not
 * a nicety. {@code PROCESSING} has to split at source into at least an
 * externally-incurred-origination-service function (valuation, legal, bureau, broker — capitalise)
 * and an internal-credit-appraisal function (exclude), and {@code OTHER} has to stop being
 * populated on any cost the bank intends to capitalise. Both are changes to the HR and
 * cost-centre feeds, not to this enum: a fifth constant here that nothing can populate would be
 * decoration. Until then {@code PROCESSING} and {@code OTHER} route to the exception queue on
 * any posting headed for the carrying amount, which is the only answer that neither overstates
 * the asset nor expenses a genuine selling commission.
 *
 * <h2>Correspondence with {@code FeePosting.COST_FUNCTIONS}</h2>
 *
 * <p>This is additive: {@code FeePosting} is unchanged and still carries a {@code String}. Every
 * constant's {@link #name()} is exactly one of the four permitted strings, in the same order, so
 * a later change can adopt this type mechanically. {@link #wireVocabulary()} exists to be
 * asserted equal to {@code FeePosting.COST_FUNCTIONS} in test — <b>a taxonomy that has drifted
 * from the vocabulary the ingestion layer populates is the defect this type must not
 * introduce</b>, and it would drift silently, because a fifth string in the record and a
 * missing constant here compile perfectly well against each other.
 *
 * <h2>Two scope observations on FR-203, both reported rather than fixed here</h2>
 *
 * <ol>
 *   <li><b>FR-203 says "a posting"; {@code FeePosting} demands the attribute only on an
 *       {@code INTEGRAL} cost.</b> That is a sound narrowing in one direction — a fee
 *       <em>received</em> served no cost function and requiring one would be meaningless — but
 *       it leaves an asymmetry. Classification is resolved by the rule set, and 03 § 3.2 says
 *       the rule set resolves it <em>using</em> the cost function; a cost posting whose
 *       attribute was absent and which therefore came back {@code AS_INCURRED} is never
 *       challenged, because the constructor's gate only fires on {@code INTEGRAL}. The absence
 *       is invisible in exactly the direction that keeps a genuine selling commission out of
 *       the carrying amount and into year-one expense. {@link #resolve} is therefore offered
 *       for every cost paid, whatever its classification, and not only for the ones already
 *       marked integral.</li>
 *   <li><b>An {@code ADMIN} cost classified {@code INTEGRAL} is a contradiction, and nothing
 *       currently detects it.</b> {@code FeePosting}'s javadoc says so explicitly and declines
 *       to re-decide classification in the projector, which is right — the same judgement in two
 *       places is two judgements that can disagree. But it leaves the control unimplemented
 *       rather than located. {@link #resolveForCapitalisation} is that control, as a value the
 *       rule set's own tests and the exception queue can consume.</li>
 * </ol>
 *
 * <p>An invariant identifier would suit the second of those — something on the order of
 * {@code FC_1("an integral cost carries a capitalisable cost function")} — but
 * {@code InvariantId} is another unit's file, so the check returns a
 * {@link CostFunctionResolution} rather than squatting on an identifier that means something
 * else. The needed identifier is reported instead.
 */
public enum CostFunction {

    /**
     * Cost of <em>sourcing</em> the exposure: DSA and DMA commission, broker and dealer fees,
     * and the incentive paid to an employee acting as a selling agent.
     *
     * <p>Capitalises. This is the constant that carries the whole point of paragraph 53's
     * parenthesis — without "including employees acting as selling agents" a bank would book a
     * branch sourcing incentive as staff cost and expense it, and the EIR would be understated
     * on every internally-sourced loan while being right on every DSA-sourced one. Reference
     * case 1's 10,000.00 DSA commission is this function, and it is 0.997 of the 1,000,000.00
     * advance once netted against the 15,000.00 processing fee received.
     */
    SELLING(CostFunctionCapitalisability.CAPITALISE,
        "ACPIR 53 positive limb: fees and commission paid to agents, including employees acting as"
            + " selling agents, advisers, brokers and dealers. Tracks IFRS 9 B5.4.8."),

    /**
     * Cost of <em>processing</em> the exposure — and therefore, as recorded, undecidable.
     *
     * <p>{@link CostFunctionCapitalisability#INDETERMINATE}. Internal credit appraisal sits in
     * paragraph 53's negative limb by name; an external valuation, title search, legal opinion
     * or bureau pull bought in for the origination sits in the positive limb as a directly
     * attributable cost. Indian fee masters file both under processing. See the finding in this
     * class's javadoc: the requirement here is a split at source, and until it exists the honest
     * answer for a posting about to be capitalised is the exception queue.
     */
    PROCESSING(CostFunctionCapitalisability.INDETERMINATE,
        "ACPIR 53 straddled: internal credit appraisal is excluded internal administrative cost,"
            + " while externally purchased origination services are directly attributable and"
            + " capitalise. The recorded function does not distinguish them (FR-203)."),

    /**
     * Internal administrative or holding cost — credit-appraisal team salary, cost of running
     * the loan operations centre, general overhead recovery.
     *
     * <p>Excluded, by the negative limb, expressly. An {@code ADMIN} cost that has been
     * classified {@code INTEGRAL} is a rule-set defect and not a borderline call: paragraph 53
     * names internal administrative cost as an exclusion, so the two statements cannot both
     * stand.
     */
    ADMIN(CostFunctionCapitalisability.EXCLUDE,
        "ACPIR 53 negative limb: internal administrative or holding costs are excluded from"
            + " transaction costs. Salary of the credit-appraisal team is the named case."),

    /**
     * The residual bucket, carrying no accounting information.
     *
     * <p>{@link CostFunctionCapitalisability#INDETERMINATE}, and for a blunter reason than
     * {@code PROCESSING}: nothing about the word places the cost on either limb. Its presence in
     * the vocabulary is what makes the vocabulary look complete while leaving FR-203's question
     * unanswered — a feed that cannot attribute a cost can populate {@code OTHER}, satisfy the
     * not-null check, and pass an unattributed cost straight through. Treated as a rejection on
     * anything headed for the carrying amount, so that populating it is not a way around the
     * requirement.
     */
    OTHER(CostFunctionCapitalisability.INDETERMINATE,
        "ACPIR 53 gives no treatment for an unattributed cost. A residual bucket is not a"
            + " classification; the posting is refused rather than assigned a limb (FR-203).");

    private final CostFunctionCapitalisability capitalisability;
    private final String acpirBasis;

    CostFunction(CostFunctionCapitalisability capitalisability, String acpirBasis) {
        this.capitalisability = capitalisability;
        this.acpirBasis = acpirBasis;
    }

    /** What ACPIR 53 says about this function — including that it says nothing. */
    public CostFunctionCapitalisability capitalisability() {
        return capitalisability;
    }

    /**
     * The paragraph and the reasoning behind {@link #capitalisability()}, in words.
     *
     * <p>Carried on the constant rather than left to a comment because it is what an auditor
     * asks for: the basis on which a cost entered or stayed out of the carrying amount. A
     * rejection message and a computation trace can then quote the authority instead of citing
     * the code.
     */
    public String acpirBasis() {
        return acpirBasis;
    }

    /** Whether a cost serving this function enters the initial carrying amount. */
    public boolean isCapitalisable() {
        return capitalisability == CostFunctionCapitalisability.CAPITALISE;
    }

    /** Whether ACPIR 53 excludes a cost serving this function outright. */
    public boolean isExcluded() {
        return capitalisability == CostFunctionCapitalisability.EXCLUDE;
    }

    /**
     * Whether this function must be refined at source before a posting carrying it can be
     * capitalised — {@code PROCESSING} and {@code OTHER}. The finding of this class, as a
     * predicate.
     */
    public boolean requiresRefinement() {
        return capitalisability == CostFunctionCapitalisability.INDETERMINATE;
    }

    /**
     * The exact string this constant corresponds to in {@code FeePosting.COST_FUNCTIONS} and in
     * the {@code FEE_POSTING.cost_function} column (04 § 2.5).
     *
     * <p>{@link #name()} by construction, and a method anyway: a call site writing the wire
     * value should say that it means the wire value, so that a future divergence between the
     * constant's name and the persisted string is one edit here rather than a search for
     * {@code name()} calls.
     */
    public String wireValue() {
        return name();
    }

    /**
     * The four permitted strings, in the order {@code FeePosting.COST_FUNCTIONS} declares them.
     *
     * <p>Ordered for the reason the record gives for ordering its own list: a rejection message
     * must read the same on every run, because an exception-queue entry is a record and a record
     * that varies between identical runs is not one.
     *
     * <p>Assert this equal to {@code FeePosting.COST_FUNCTIONS}. That assertion is the whole
     * defence against the failure mode this type introduces — a taxonomy that has drifted from
     * the vocabulary the ingestion layer actually populates, which no compiler can see.
     */
    public static List<String> wireVocabulary() {
        CostFunction[] declared = values();
        List<String> vocabulary = new ArrayList<>(declared.length);
        for (CostFunction function : declared) {
            vocabulary.add(function.wireValue());
        }
        return List.copyOf(vocabulary);
    }

    /**
     * The recognised functions ACPIR 53 places in its positive limb.
     *
     * <p>Derived from each constant's own decision rather than listed, so that adding a constant
     * cannot leave this behind. One element today; a list rather than a single constant because
     * closing the {@code PROCESSING} gap adds a second (an externally-purchased-origination-service
     * function) and a call site written against {@code SELLING} alone would then be quietly wrong.
     */
    public static List<CostFunction> capitalisableFunctions() {
        List<CostFunction> capitalisable = new ArrayList<>();
        for (CostFunction function : values()) {
            if (function.isCapitalisable()) {
                capitalisable.add(function);
            }
        }
        return List.copyOf(capitalisable);
    }

    /**
     * Parses a recorded attribute, normalised exactly as {@code FeePosting} normalises it —
     * trimmed, upper-cased in {@link Locale#ROOT}.
     *
     * <p>The locale is not incidental. {@code "admin".toUpperCase()} under a Turkish default
     * locale yields {@code "ADMİN"}, which matches nothing, so a cost posting would be refused
     * on one JVM and accepted on another. {@code FeePosting} pins {@code Locale.ROOT} for that
     * reason and this must pin the same one or the two disagree about the same input.
     *
     * @return the function, or empty where the attribute is absent or outside the vocabulary —
     *     the two cases {@link #resolve} distinguishes
     */
    public static Optional<CostFunction> from(String recorded) {
        String normalised = normalise(recorded);
        if (normalised == null) {
            return Optional.empty();
        }
        for (CostFunction function : values()) {
            if (function.name().equals(normalised)) {
                return Optional.of(function);
            }
        }
        return Optional.empty();
    }

    /**
     * FR-203's literal limb, as a value: "Reject a posting whose {@code cost_function} attribute
     * is absent."
     *
     * <p>Applicable to <b>every cost the bank pays</b>, not only one already classified
     * {@code INTEGRAL} — see observation 1 in this class's javadoc. Acceptance here means the
     * attribute is populated and inside the vocabulary; it does not mean the cost capitalises.
     * {@code ADMIN} is accepted and capitalises nothing. Use {@link #resolveForCapitalisation}
     * where the question is whether the cost may enter the carrying amount.
     */
    public static CostFunctionResolution resolve(String recorded) {
        String normalised = normalise(recorded);
        if (normalised == null) {
            return CostFunctionResolution.rejected(null, CostFunctionResolution.Cause.ABSENT,
                "cost_function is absent. ACPIR 53 capitalises a selling-agent incentive and"
                    + " excludes internal credit-appraisal cost, so a cost with no recorded"
                    + " function cannot be placed on either limb and is refused rather than"
                    + " defaulted to either (FR-203). Expected one of " + wireVocabulary());
        }
        Optional<CostFunction> parsed = from(normalised);
        if (parsed.isEmpty()) {
            return CostFunctionResolution.rejected(null,
                CostFunctionResolution.Cause.OUTSIDE_VOCABULARY,
                "cost_function '" + recorded + "' is not one of " + wireVocabulary()
                    + " (04 § 2.5). A value the vocabulary does not contain is a defect in the"
                    + " feed that populated it; accepting it would make the vocabulary whatever"
                    + " the last feed said it was");
        }
        CostFunction function = parsed.get();
        return CostFunctionResolution.accepted(function,
            "cost_function " + function.wireValue() + "; " + function.acpirBasis());
    }

    /**
     * The stronger gate: may a cost serving this recorded function enter the initial carrying
     * amount and amortise through the EIR?
     *
     * <p>Everything {@link #resolve} refuses, plus the two cases where the attribute is valid
     * and capitalising it would still be wrong:
     *
     * <ul>
     *   <li>{@code ADMIN} — {@link CostFunctionResolution.Cause#EXCLUDED_BY_ACPIR_53}. Paragraph
     *       53 excludes internal administrative cost by name, so where the rule set has
     *       classified the posting {@code INTEGRAL} the two statements contradict and the defect
     *       is in the rule set. This gate is handed the attribute and not the classification, so
     *       the rejection detail states that conditionally rather than asserting a classification
     *       it was never given; either way the cost may not be capitalised.
     *   <li>{@code PROCESSING} and {@code OTHER} —
     *       {@link CostFunctionResolution.Cause#INDETERMINATE}. The finding: the recorded
     *       function does not say which limb, and both defaults are wrong in the direction
     *       nobody checks. Capitalising sweeps credit-appraisal salary into the asset;
     *       expensing writes off a genuine external origination cost in year one.
     * </ul>
     *
     * <p>The defect this catches, concretely: a bank's fee master maps
     * {@code PROC_FEE_PAID -> INTEGRAL}, the cost centre populates {@code PROCESSING} for both
     * its in-house appraisal recharge and its external valuer invoices, and the engine
     * capitalises the lot. Nothing downstream can distinguish the resulting EIR from a correct
     * one — which is the standing argument (04 § 3) for stopping the contract instead.
     */
    public static CostFunctionResolution resolveForCapitalisation(String recorded) {
        CostFunctionResolution presence = resolve(recorded);
        if (!presence.isAccepted()) {
            return presence;
        }
        CostFunction function = presence.function();
        if (function.isCapitalisable()) {
            return presence;
        }
        CostFunctionResolution.Cause cause = function.isExcluded()
            ? CostFunctionResolution.Cause.EXCLUDED_BY_ACPIR_53
            : CostFunctionResolution.Cause.INDETERMINATE;
        // The wording of the ADMIN case is conditional on purpose. This method is handed the
        // attribute and nothing else, so it does not know the posting's classification — and an
        // exception-queue entry that asserted one would be a fabricated fact in the artefact an
        // auditor reads. It states what it knows (paragraph 53 excludes this function) and what
        // follows only IF the rule set said INTEGRAL.
        String why = function.isExcluded()
            ? "cost_function " + function.wireValue() + " is internal administrative cost,"
                + " excluded by ACPIR 53, and cannot enter the carrying amount. Where the rule set"
                + " has classified this posting " + FeeClassification.INTEGRAL + " the two"
                + " statements contradict each other, and the defect is in the rule set rather"
                + " than in the attribute — not a borderline call, since paragraph 53 names"
                + " internal administrative cost as an exclusion"
            : "cost_function " + function.wireValue() + " does not place this cost on either limb"
                + " of ACPIR 53, so it can be neither capitalised nor expensed on the strength of"
                + " the attribute recorded. The function must be refined at source — selling"
                + " versus internal credit appraisal (FR-203, 08 § 0) — before this posting can"
                + " enter the carrying amount";
        return CostFunctionResolution.rejected(function, cause, why + ". " + function.acpirBasis());
    }

    /**
     * Trim and upper-case in {@link Locale#ROOT}, collapsing null, empty and whitespace to null.
     *
     * <p>Three spellings of absent because all three arrive in practice: a null column, an empty
     * string from a CSV with a trailing comma, and a space from a fixed-width extract. Treating
     * only null as absent lets the other two through the presence check with no function
     * recorded, which is the failure FR-203 exists to prevent. {@code FeePosting} collapses the
     * same three; this must agree with it exactly or the two boundaries disagree about the same
     * feed.
     */
    private static String normalise(String recorded) {
        if (recorded == null) {
            return null;
        }
        String trimmed = recorded.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
