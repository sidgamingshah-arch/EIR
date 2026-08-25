package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.fee.CostFunctionResolution.Cause;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The ACPIR 53 cost-function taxonomy (FR-203).
 *
 * <p>Two things are under test and they are different in kind. One is the accounting content —
 * which of the four recorded functions ACPIR 53 capitalises, which it excludes, and which it
 * cannot place at all. The other is <b>correspondence</b>: this type is additive, sitting beside
 * {@code FeePosting}'s four untyped strings rather than replacing them, and the failure it could
 * introduce is drift. A fifth string in the record, or a renamed constant here, compiles
 * perfectly well against the other side and would be found in production by a cost posting the
 * projector accepted and the policy layer had never heard of.
 *
 * <p><b>Every expected value below is derived from the documents, never from running the code.</b>
 * The vocabulary is the {@code cost_function} row of 04 § 2.5. The capitalisability of each
 * function is read off ACPIR 53's two limbs as quoted in the ACPIR reference § 1 table and
 * discussed in 01 § "ACPIR 53 draws the line at selling, not processing" and 03 § 3.2. The
 * derivations are stated at each assertion.
 */
class CostFunctionTest {

    /**
     * The four permitted values, transcribed from the {@code cost_function} row of the
     * {@code FEE_POSTING} table in 04 § 2.5:
     * "{@code SELLING} | {@code PROCESSING} | {@code ADMIN} | {@code OTHER}. Rejected if absent
     * (FR-203) — the ACPIR 53 selling-versus-processing line."
     *
     * <p>Written out here rather than read from either side of the correspondence, so that the
     * two sides are checked against the specification and not merely against each other. Two
     * implementations that drifted together would satisfy an assertion that only compared them.
     */
    private static final List<String> VOCABULARY_PER_SPEC =
        List.of("SELLING", "PROCESSING", "ADMIN", "OTHER");

    private static final LocalDate POSTED_ON = LocalDate.of(2027, 4, 1);

    /** Reference case 1's DSA commission: 10,000.00 paid, capitalised under ACPIR 53. */
    private static final Money DSA_COMMISSION = Money.inr("10000.00");

    /**
     * Constructs the posting {@code FeePosting} guards hardest — an integral cost, which is the
     * only shape whose constructor demands a cost function — so that the two boundaries can be
     * compared on identical input.
     */
    private static FeePosting integralCostCarrying(String costFunction) {
        return FeePosting.paid(
            "DSA-COMM", DSA_COMMISSION, POSTED_ON, FeeClassification.INTEGRAL, costFunction);
    }

    @Nested
    @DisplayName("correspondence with FeePosting.COST_FUNCTIONS — the drift defect")
    class Correspondence {

        @Test
        @DisplayName("the taxonomy is exactly the four values 04 § 2.5 permits, in that order")
        void vocabularyMatchesTheSpecification() {
            // Order is load-bearing, not cosmetic: FeePosting orders its own list so that a
            // rejection message reads the same on every run, and an exception-queue entry that
            // varies between identical runs is not a record. containsExactly, therefore, not
            // containsExactlyInAnyOrder.
            assertThat(CostFunction.wireVocabulary())
                .as("cost_function vocabulary per 04 § 2.5, in the order FeePosting declares")
                .containsExactlyElementsOf(VOCABULARY_PER_SPEC);
        }

        @Test
        @DisplayName("the taxonomy has not drifted from the strings the ingestion layer populates")
        void vocabularyMatchesFeePosting() {
            // THE assertion this class exists for. FeePosting is in eir-calc and unedited; a
            // change to either list that is not made to the other lands here. Both directions
            // are stated because they fail differently: an extra string in the record is a value
            // the policy layer will refuse to place, and an extra constant here is a taxonomy
            // entry no feed can ever populate.
            assertThat(CostFunction.wireVocabulary())
                .as("CostFunction.wireVocabulary() vs FeePosting.COST_FUNCTIONS")
                .containsExactlyElementsOf(FeePosting.COST_FUNCTIONS);
            assertThat(FeePosting.COST_FUNCTIONS)
                .as("FeePosting.COST_FUNCTIONS vs 04 § 2.5")
                .containsExactlyElementsOf(VOCABULARY_PER_SPEC);
        }

        @Test
        @DisplayName("every permitted string resolves to the constant of the same name")
        void everyStringResolves() {
            for (String recorded : VOCABULARY_PER_SPEC) {
                assertThat(CostFunction.from(recorded))
                    .as("from(\"%s\")", recorded)
                    .isPresent()
                    .get()
                    .extracting(CostFunction::wireValue)
                    .isEqualTo(recorded);
            }
        }

        @Test
        @DisplayName("every constant is a cost function FeePosting itself accepts")
        void everyConstantIsAcceptedByFeePosting() {
            // Proves adoptability, which is the point of an additive type: a later change that
            // replaces the String parameter with this enum must not narrow or widen what the
            // projector accepts. FeePosting throws IllegalArgumentException on a value outside
            // its own list, so a constant it rejects would surface here rather than at the point
            // some future call site passes wireValue() into it.
            for (CostFunction function : CostFunction.values()) {
                assertThat(catchThrowable(() -> integralCostCarrying(function.wireValue())))
                    .as("FeePosting accepts cost function %s", function)
                    .isNull();
            }
        }

        @Test
        @DisplayName("a fifth cost function is refused by both boundaries, not just one")
        void inventedFunctionRefusedByBoth() {
            // "DSA" is the realistic invention: it is what an Indian bank's sourcing feed calls
            // the thing, and it means SELLING. Accepting it here would be accepting that the
            // vocabulary is whatever the last feed said it was — and note the asymmetry in how
            // the two boundaries refuse it. FeePosting throws, because an unattributed cost must
            // not be constructible; this returns a value, because a ten-million-contract run has
            // to collect what it could not classify and carry on (FR-905).
            assertThatIllegalArgumentException()
                .as("FeePosting refuses a value outside its vocabulary")
                .isThrownBy(() -> integralCostCarrying("DSA"))
                .withMessageContaining("costFunction must be one of");

            CostFunctionResolution resolution = CostFunction.resolve("DSA");
            assertThat(resolution.isAccepted()).as("resolve(\"DSA\") accepted").isFalse();
            assertThat(resolution.cause()).isEqualTo(Cause.OUTSIDE_VOCABULARY);
        }
    }

    @Nested
    @DisplayName("ACPIR 53: the selling-versus-appraisal boundary, per function")
    class Acpir53Boundary {

        @Test
        @DisplayName("SELLING capitalises — the positive limb, including employee selling agents")
        void sellingCapitalises() {
            // ACPIR 53: "Include fees and commission paid to agents (including employees acting
            // as selling agents), advisers, brokers and dealers." Reference case 1's 10,000.00
            // DSA commission is this function and is capitalised in the fixture.
            assertThat(CostFunction.SELLING.capitalisability())
                .as("ACPIR 53 positive limb")
                .isEqualTo(CostFunctionCapitalisability.CAPITALISE);
            assertThat(CostFunction.SELLING.isCapitalisable()).isTrue();
            assertThat(CostFunction.SELLING.isExcluded()).isFalse();
            assertThat(CostFunction.SELLING.requiresRefinement()).isFalse();
        }

        @Test
        @DisplayName("ADMIN is excluded — internal administrative cost, excluded by name")
        void adminExcluded() {
            // ACPIR 53: "Exclude debt premiums or discounts, financing costs, and internal
            // administrative or holding costs." The credit-appraisal team's salary is the named
            // case (01 §, 03 § 3.2, and the ACPIR reference's "read paragraph 53 twice" note).
            assertThat(CostFunction.ADMIN.capitalisability())
                .as("ACPIR 53 negative limb")
                .isEqualTo(CostFunctionCapitalisability.EXCLUDE);
            assertThat(CostFunction.ADMIN.isExcluded()).isTrue();
            assertThat(CostFunction.ADMIN.isCapitalisable()).isFalse();
        }

        @Test
        @DisplayName("PROCESSING and OTHER are undecidable — the finding, asserted")
        void processingAndOtherAreIndeterminate() {
            // The defect a boolean flag would have introduced. PROCESSING spans both limbs:
            // internal credit appraisal is excluded by name, while an external valuation, title
            // search or bureau charge bought in for the origination is a directly attributable
            // transaction cost that capitalises. OTHER is a residual bucket and carries no
            // accounting information at all. Neither can be given a treatment in code without
            // deciding an accounting question that belongs to the fee master and the ACPIR 57
            // committee, whose standing agenda item 5 is exactly this: "Sourcing versus
            // processing cost. Rule separating employee selling-agent incentives (capitalise)
            // from internal appraisal cost (expense)."
            assertThat(CostFunction.PROCESSING.capitalisability())
                .as("PROCESSING straddles both limbs of ACPIR 53")
                .isEqualTo(CostFunctionCapitalisability.INDETERMINATE);
            assertThat(CostFunction.OTHER.capitalisability())
                .as("OTHER carries no accounting information")
                .isEqualTo(CostFunctionCapitalisability.INDETERMINATE);

            assertThat(CostFunction.PROCESSING.requiresRefinement()).isTrue();
            assertThat(CostFunction.OTHER.requiresRefinement()).isTrue();
            assertThat(CostFunction.PROCESSING.capitalisability().isDecided()).isFalse();
        }

        @Test
        @DisplayName("the vocabulary decides half the question: two of four functions")
        void halfTheVocabularyIsUndecidable() {
            // Stated as a count so that the finding cannot be quietly closed by giving
            // PROCESSING or OTHER a treatment. Two of four is derived by reading the ACPIR 53
            // limbs against the 04 § 2.5 values, not by counting what the enum happens to say:
            // SELLING is named in the positive limb, ADMIN in the negative limb, and neither
            // limb mentions processing or a residual.
            assertThat(CostFunction.values())
                .as("functions ACPIR 53 cannot place as recorded")
                .filteredOn(CostFunction::requiresRefinement)
                .containsExactly(CostFunction.PROCESSING, CostFunction.OTHER);

            assertThat(CostFunction.capitalisableFunctions())
                .as("only SELLING is unambiguously within ACPIR 53's positive limb today")
                .containsExactly(CostFunction.SELLING);
        }

        @Test
        @DisplayName("every function cites the paragraph its treatment comes from")
        void everyFunctionCitesItsBasis() {
            // An auditor's question about a cost in the carrying amount is "on what basis", and
            // the answer has to travel with the classification rather than living in a comment.
            for (CostFunction function : CostFunction.values()) {
                assertThat(function.acpirBasis())
                    .as("basis for %s", function)
                    .isNotBlank()
                    .contains("ACPIR 53");
            }
        }
    }

    @Nested
    @DisplayName("FR-203's presence limb: reject a posting whose cost_function is absent")
    class PresenceGate {

        @Test
        @DisplayName("null, empty and whitespace are all absent, and all rejected")
        void threeSpellingsOfAbsent() {
            // All three arrive in practice: a null column, an empty string from a CSV with a
            // trailing comma, and a space from a fixed-width extract. Treating only null as
            // absent would let the other two satisfy a not-null check with no function recorded,
            // which is precisely the hole FR-203 exists to close. FeePosting collapses the same
            // three, so the two boundaries must agree about the same feed.
            for (String absent : new String[] {null, "", "   ", "\t"}) {
                CostFunctionResolution resolution = CostFunction.resolve(absent);
                assertThat(resolution.isAccepted())
                    .as("resolve(%s) accepted", absent == null ? "null" : "'" + absent + "'")
                    .isFalse();
                assertThat(resolution.cause()).isEqualTo(Cause.ABSENT);
                assertThat(resolution.resolved()).isEmpty();
                assertThat(resolution.detail())
                    .as("the rejection names the requirement, for the queue entry")
                    .contains("FR-203");
            }
        }

        @Test
        @DisplayName("a rejection routes to MISSING_COST_FUNCTION and stops the contract")
        void rejectionRoutesToTheQueue() {
            // 04 § 3 and FR-905: the alternative to raising an exception is publishing a wrong
            // rate that nothing downstream can distinguish from a right one. Both properties are
            // asserted because both matter — the category decides who fixes it, stopsTheContract
            // decides whether a figure is published meanwhile.
            CostFunctionResolution resolution = CostFunction.resolve(null);
            assertThat(resolution.exception())
                .isEqualTo(ExceptionCategory.MISSING_COST_FUNCTION);
            assertThat(resolution.exception().stopsTheContract())
                .as("an unattributed cost yields no figure at all")
                .isTrue();
            assertThat(resolution.exception().blocksClose()).isTrue();
        }

        @Test
        @DisplayName("normalisation matches FeePosting: trimmed, upper-cased")
        void normalisationMatchesFeePosting() {
            // FeePosting normalises with trim().toUpperCase(Locale.ROOT) and stores the result,
            // so '  selling  ' is a SELLING posting there. A policy layer that refused the same
            // input would reject postings the projector had already accepted, and the
            // disagreement would look like a data problem rather than a code one.
            assertThat(CostFunction.from("  selling  "))
                .as("padded lower case resolves")
                .contains(CostFunction.SELLING);
            assertThat(integralCostCarrying("  selling  ").costFunction())
                .as("FeePosting stores the same normalised value")
                .isEqualTo(CostFunction.SELLING.wireValue());
        }

        @Test
        @DisplayName("upper-casing is locale-independent, so the same feed resolves on any JVM")
        void upperCasingIsLocaleIndependent() {
            // Turkish dotted-capital-I: "admin".toUpperCase() under tr-TR is "ADMİN" (U+0130),
            // which matches no constant. Under a default locale of tr-TR a cost posting would
            // therefore be refused on one JVM and accepted on another, from identical input —
            // and the engine's determinism requirement (DT-1, a re-run reproduces published
            // figures bit-identically) does not survive that. Locale.ROOT is pinned in both
            // FeePosting and CostFunction for this reason; this test is what notices if either
            // drops it.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.of("tr", "TR"));
                assertThat(CostFunction.from("admin"))
                    .as("lower-case admin under a Turkish default locale")
                    .contains(CostFunction.ADMIN);
                assertThat(integralCostCarrying("admin").costFunction())
                    .as("FeePosting agrees under the same locale")
                    .isEqualTo("ADMIN");
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("ADMIN is accepted by the presence gate and still capitalises nothing")
        void acceptanceIsNotCapitalisability() {
            // The distinction a single boolean would have destroyed. FR-203's literal limb asks
            // only that the attribute be populated; ACPIR 53 then decides the treatment. A call
            // site that read acceptance as permission to capitalise would sweep every cost whose
            // attribute merely parsed into the gross carrying amount.
            CostFunctionResolution resolution = CostFunction.resolve("ADMIN");
            assertThat(resolution.isAccepted()).as("the attribute is populated and valid").isTrue();
            assertThat(resolution.capitalises())
                .as("ACPIR 53 excludes internal administrative cost regardless")
                .isFalse();
            assertThat(resolution.exception()).isNull();
        }
    }

    @Nested
    @DisplayName("the capitalisation gate: may this cost enter the carrying amount?")
    class CapitalisationGate {

        @Test
        @DisplayName("a selling-agent incentive passes — ACPIR 53's whole point")
        void sellingPasses() {
            CostFunctionResolution resolution = CostFunction.resolveForCapitalisation("SELLING");
            assertThat(resolution.isAccepted()).isTrue();
            assertThat(resolution.capitalises())
                .as("an incentive paid to an employee acting as a selling agent capitalises")
                .isTrue();
            assertThat(resolution.function()).isEqualTo(CostFunction.SELLING);
        }

        @Test
        @DisplayName("ADMIN classified INTEGRAL is a contradiction, and is named as one")
        void adminOnAnIntegralPostingIsARuleSetDefect() {
            // FeePosting's javadoc says an INTEGRAL posting carrying ADMIN "is a rule-set defect,
            // and it belongs in the rule set's own control, not in a second opinion held by the
            // projector" — correctly declining to re-decide classification, but leaving the
            // control unimplemented rather than located. This is where it is located. Note that
            // FeePosting itself constructs such a posting happily, which is the gap:
            assertThat(catchThrowable(() -> integralCostCarrying("ADMIN")))
                .as("the projector does not refuse an ADMIN cost classified INTEGRAL")
                .isNull();

            CostFunctionResolution resolution = CostFunction.resolveForCapitalisation("ADMIN");
            assertThat(resolution.isAccepted()).isFalse();
            assertThat(resolution.cause()).isEqualTo(Cause.EXCLUDED_BY_ACPIR_53);
            assertThat(resolution.function())
                .as("the offending function is carried so the queue entry can name it")
                .isEqualTo(CostFunction.ADMIN);
            // The wording is conditional — "where the rule set has classified this posting
            // INTEGRAL" — because the gate is handed the attribute and not the classification.
            // An entry that flatly asserted INTEGRAL would be stating, in the artefact an auditor
            // reads, a fact the engine did not check on this call.
            assertThat(resolution.detail())
                .as("names the contradiction without asserting a classification it was not given")
                .contains("Where the rule set has classified this posting INTEGRAL")
                .contains("contradict");
        }

        @Test
        @DisplayName("PROCESSING and OTHER stop the posting rather than guessing a limb")
        void indeterminateFunctionsStopThePosting() {
            // The concrete defect: a fee master maps PROC_FEE_PAID -> INTEGRAL, the cost centre
            // populates PROCESSING for both its in-house appraisal recharge and its external
            // valuer invoices, and the engine capitalises the lot. On reference case 1 the entire
            // net integral fee is 5,000.00 on a 1,000,000.00 advance and moves the EIR 56.6 basis
            // points, so a mis-swept appraisal cost centre is the same order of magnitude as the
            // effect being measured — and nothing downstream can tell the resulting rate from a
            // correct one.
            for (CostFunction undecidable : List.of(CostFunction.PROCESSING, CostFunction.OTHER)) {
                CostFunctionResolution resolution =
                    CostFunction.resolveForCapitalisation(undecidable.wireValue());
                assertThat(resolution.isAccepted())
                    .as("%s may not be capitalised on the attribute as recorded", undecidable)
                    .isFalse();
                assertThat(resolution.cause()).isEqualTo(Cause.INDETERMINATE);
                assertThat(resolution.capitalises()).isFalse();
                assertThat(resolution.function()).isEqualTo(undecidable);
                assertThat(resolution.exception().stopsTheContract()).isTrue();
                assertThat(resolution.detail())
                    .as("the queue entry says what refinement is needed")
                    .contains("refined at source");
            }
        }

        @Test
        @DisplayName("the stronger gate still rejects everything the presence gate rejects")
        void delegatesToThePresenceGate() {
            // Composition rather than a second copy of the parsing rules: two gates that parse
            // the attribute separately are two gates that can disagree about '  Selling '.
            assertThat(CostFunction.resolveForCapitalisation(null).cause())
                .isEqualTo(Cause.ABSENT);
            assertThat(CostFunction.resolveForCapitalisation("SALES").cause())
                .isEqualTo(Cause.OUTSIDE_VOCABULARY);
            assertThat(CostFunction.resolveForCapitalisation(" selling ").function())
                .as("normalisation applies to the stronger gate too")
                .isEqualTo(CostFunction.SELLING);
        }

        @Test
        @DisplayName("exactly one of the four functions may be capitalised as recorded")
        void onlyOneFunctionCapitalises() {
            // The finding restated at the gate: three of the four recorded values cannot put a
            // cost into the carrying amount today — one because ACPIR 53 excludes it, two because
            // ACPIR 53 cannot place it. That is the measure of how far the source data has to move
            // before FR-203 is closed (08 § 0: "a months-long exercise with no shortcut").
            assertThat(CostFunction.values())
                .filteredOn(f -> CostFunction.resolveForCapitalisation(f.wireValue()).capitalises())
                .containsExactly(CostFunction.SELLING);
        }
    }

    @Nested
    @DisplayName("the resolution record cannot carry a contradiction")
    class ResolutionInvariants {

        @Test
        @DisplayName("an accepted resolution raises no exception and names its function")
        void acceptedIsCoherent() {
            assertThatIllegalArgumentException()
                .as("accepted with a queue category")
                .isThrownBy(() -> new CostFunctionResolution(
                    CostFunction.SELLING, Cause.ACCEPTED,
                    ExceptionCategory.MISSING_COST_FUNCTION, "d"))
                .withMessageContaining("raises no exception");

            assertThatNullPointerException()
                .as("accepted with no function")
                .isThrownBy(() -> new CostFunctionResolution(null, Cause.ACCEPTED, null, "d"));
        }

        @Test
        @DisplayName("a rejection must name a queue category, or the item is lost")
        void rejectionNeedsACategory() {
            // A rejection with no category is an item nobody is asked to fix: it fails the
            // posting and then vanishes, which is worse than either accepting or refusing it.
            assertThatNullPointerException()
                .isThrownBy(() -> new CostFunctionResolution(null, Cause.ABSENT, null, "d"))
                .withMessageContaining("queue category");
        }

        @Test
        @DisplayName("a cause that recognised no function may not carry one, and vice versa")
        void causeAndFunctionAgree() {
            // Guards the two readings that would mislead an exception report: an ABSENT entry
            // naming a cost function it did not have, and an INDETERMINATE entry that cannot say
            // which function was undecidable.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CostFunctionResolution(
                    CostFunction.SELLING, Cause.ABSENT,
                    ExceptionCategory.MISSING_COST_FUNCTION, "d"))
                .withMessageContaining("no function was recognised");

            assertThatNullPointerException()
                .isThrownBy(() -> new CostFunctionResolution(
                    null, Cause.INDETERMINATE, ExceptionCategory.MISSING_COST_FUNCTION, "d"))
                .withMessageContaining("must be carried");
        }

        @Test
        @DisplayName("a cause cannot be routed to a category it does not map to")
        void categoryIsDerivedFromTheCauseOnEveryPath() {
            // A record's canonical constructor is public whether or not the factory is the
            // intended door, so the "category derived from the cause" guarantee has to be a
            // constructor check and not a property of one code path. The defect otherwise: the
            // same absent attribute reaching MISSING_COST_FUNCTION from one call site and
            // MISSING_MANDATORY_FIELD from another, so the queue shows one defect as two and
            // neither count reconciles.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CostFunctionResolution(
                    null, Cause.ABSENT, ExceptionCategory.MISSING_MANDATORY_FIELD, "d"))
                .withMessageContaining("derived from the cause");
        }

        @Test
        @DisplayName("a resolution with no detail is refused")
        void detailIsMandatory() {
            // The detail is the exception-queue entry's whole content. A blank one leaves an
            // auditor with a stopped contract and no statement of why.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CostFunctionResolution(
                    CostFunction.SELLING, Cause.ACCEPTED, null, "   "))
                .withMessageContaining("name the defect");
        }

        @Test
        @DisplayName("ACCEPTED is not a rejection cause")
        void acceptedIsNotARejection() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> CostFunctionResolution.rejected(
                    CostFunction.SELLING, Cause.ACCEPTED, "d"))
                .withMessageContaining("not a rejection cause");
        }

        @Test
        @DisplayName("describe() states the cause and the category it routes to")
        void describeIsReadable() {
            assertThat(CostFunction.resolveForCapitalisation("PROCESSING").describe())
                .as("one line an exception report can print")
                .contains("rejected", "INDETERMINATE", "MISSING_COST_FUNCTION");
            assertThat(CostFunction.resolve("SELLING").describe())
                .contains("SELLING", "accepted");
        }
    }
}
