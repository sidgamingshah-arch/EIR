package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The correspondence between this taxonomy and {@code FeePosting}'s four untyped strings, as a
 * property over arbitrary recorded attributes rather than over the four happy-path values.
 *
 * <p><b>Why a property and not a table.</b> {@link CostFunctionTest.Correspondence} pins the four
 * values and their order; that catches a renamed constant or a fifth string. What it cannot catch
 * is a divergence in how the two sides <em>treat</em> an input neither list mentions — a padded
 * mixed-case value, an empty string, a plausible invention like {@code "Dsa"}. Those are the
 * inputs a real HR or cost-centre extract actually produces, and a boundary pair that disagrees
 * about them fails asymmetrically: the projector constructs a posting the policy layer would have
 * quarantined, or the policy layer clears one the projector will refuse. Either way the
 * disagreement presents as a data problem and is diagnosed as one, for as long as that takes.
 *
 * <p>The expected value in each property is {@code FeePosting}'s own behaviour, which is
 * legitimate here and nowhere else in this suite: {@code FeePosting} is not the code under test,
 * it is the specification of the vocabulary this type must map onto, and the equivalence <em>is</em>
 * the requirement (the unit is additive; a taxonomy that has drifted from the strings the
 * ingestion layer populates is the defect to prevent).
 */
class CostFunctionCorrespondencePropertyTest {

    private static final LocalDate POSTED_ON = LocalDate.of(2027, 4, 1);
    private static final Money COST_PAID = Money.inr("10000.00");

    /**
     * Whether {@code FeePosting} will construct an integral cost carrying this attribute.
     *
     * <p>The integral-cost shape is chosen because it is the only one whose constructor demands a
     * cost function: {@code classification == INTEGRAL} and a negative amount together arm the
     * FR-203 gate. A fee received, or a cost classified {@code AS_INCURRED}, is accepted with no
     * attribute at all, so it would test nothing.
     */
    private static boolean feePostingAccepts(String recorded) {
        return catchThrowable(() -> FeePosting.paid(
            "COST-1", COST_PAID, POSTED_ON, FeeClassification.INTEGRAL, recorded)) == null;
    }

    /**
     * Attributes shaped like the ones a real extract produces: the four permitted values in
     * assorted casing and padding, plausible inventions, empty and whitespace-only fields, and
     * null.
     */
    @Provide
    Arbitrary<String> recordedAttributes() {
        Arbitrary<String> permitted = Combinators.combine(
                Arbitraries.of("SELLING", "PROCESSING", "ADMIN", "OTHER"),
                Arbitraries.integers().between(0, 2),
                Arbitraries.strings().withChars(' ', '\t').ofMaxLength(3))
            .as((word, casing, padding) -> padding + recase(word, casing) + padding);
        // Alphabetic junk stands in for a feed that invented its own vocabulary — "DSA", "SALES",
        // "CreditAppraisal". The properties below are equivalences, so a generator that happened
        // to produce a permitted value would not weaken them.
        Arbitrary<String> invented = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> absent = Arbitraries.of("", " ", "   ", "\t", "\n ");
        return Arbitraries.oneOf(permitted, invented, absent).injectNull(0.05);
    }

    private static String recase(String word, int casing) {
        return switch (casing) {
            case 0 -> word;
            case 1 -> word.toLowerCase(Locale.ROOT);
            default -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    @Property
    void acceptanceAgreesWithFeePostingOnEveryInput(@ForAll("recordedAttributes") String recorded) {
        // The equivalence, in both directions at once. A value this gate accepts must be one the
        // projector will construct a posting from, and a value it refuses must be one the
        // projector refuses too — otherwise the two boundaries are enforcing different
        // vocabularies while appearing to enforce one.
        CostFunctionResolution resolution = CostFunction.resolve(recorded);
        assertThat(resolution.isAccepted())
            .as("CostFunction.resolve vs FeePosting on attribute %s",
                recorded == null ? "null" : "'" + recorded + "'")
            .isEqualTo(feePostingAccepts(recorded));
    }

    @Property
    void anAcceptedAttributeResolvesToTheValueFeePostingStores(
        @ForAll("recordedAttributes") String recorded) {
        CostFunctionResolution resolution = CostFunction.resolve(recorded);
        if (!resolution.isAccepted()) {
            return;
        }
        // Not merely "both accept": both must land on the same value. FeePosting stores the
        // normalised string on the record and the projector carries it into the computation
        // trace, so a taxonomy that accepted ' selling ' as OTHER — or normalised differently —
        // would produce an audit trail whose cost function contradicted its own treatment.
        FeePosting posting = FeePosting.paid(
            "COST-1", COST_PAID, POSTED_ON, FeeClassification.INTEGRAL, recorded);
        assertThat(resolution.function().wireValue())
            .as("resolved function vs the value FeePosting normalised and stored")
            .isEqualTo(posting.costFunction());
    }

    @Property
    void onlyASellingAttributeEverCapitalises(@ForAll("recordedAttributes") String recorded) {
        // ACPIR 53's positive limb names agents, advisers, brokers, dealers and employees acting
        // as selling agents — of the four recorded functions, only SELLING is inside it. So over
        // every input the engine can be handed, at most one normalised value may put a cost into
        // the gross carrying amount. The expected value here is read off paragraph 53, not off
        // the enum: any other value reaching this assertion means a treatment was widened
        // without the paragraph changing.
        CostFunctionResolution resolution = CostFunction.resolveForCapitalisation(recorded);
        if (resolution.capitalises()) {
            assertThat(recorded)
                .as("the only attribute ACPIR 53 lets capitalise")
                .isNotNull();
            assertThat(recorded.trim().toUpperCase(Locale.ROOT)).isEqualTo("SELLING");
        }
    }

    @Property
    void theCapitalisationGateIsNeverLooserThanThePresenceGate(
        @ForAll("recordedAttributes") String recorded) {
        // Monotonicity, because the stronger gate is defined as the weaker one plus two further
        // refusals. If it ever accepts something resolve() refused, the two have grown separate
        // parsing rules and the FR-203 presence check can be bypassed by asking the other
        // question.
        boolean presence = CostFunction.resolve(recorded).isAccepted();
        boolean capitalisation = CostFunction.resolveForCapitalisation(recorded).isAccepted();
        assertThat(presence || !capitalisation)
            .as("resolveForCapitalisation accepted an attribute resolve rejected: %s", recorded)
            .isTrue();
    }
}
