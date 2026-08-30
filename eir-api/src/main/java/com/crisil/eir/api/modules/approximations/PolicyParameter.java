package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.calc.projection.RevolvingApproximation;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestRecord;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import java.util.List;
import java.util.Objects;

/**
 * One figure or rule that decides which shortcuts this register catches, with its provenance.
 *
 * <p><b>Why the register publishes its own thresholds.</b> A report on approximations whose own
 * boundaries are undocumented has the defect it is reporting on. 03 § 10.2's whole complaint is
 * that "we approximated because it was immaterial" is only a complete answer "when the materiality
 * assessment exists on paper with a number attached" — and the numbers deciding <em>which</em>
 * instruments this register calls a deep discount, or how long a back-test stays current, are
 * exactly such numbers. Two of them are in the specification, one is a policy default somebody
 * chose, one has no default at all, and one is a limit of this engine rather than of policy. A
 * reader cannot weigh the report without being told which is which, so {@link Provenance} is on
 * the wire beside every value.
 *
 * @param name       the constant, named as it is declared, so a reader can open the file
 * @param value      its value as text; figures cross the wire as strings, never numbers
 * @param provenance where the value comes from and what standing it has
 * @param reference  the specification section or requirement behind it
 * @param note       why it matters and what a wrong value would do
 */
public record PolicyParameter(
    String name,
    String value,
    PolicyParameter.Provenance provenance,
    String reference,
    String note) {

    /** What standing a value has, which is not derivable from the value. */
    public enum Provenance {

        /** Published in the specification. Changing it is a specification change. */
        SPECIFICATION("published in the specification"),

        /**
         * Declared in code as a policy default because the specification publishes a mechanism
         * and no number. <b>Not a specification figure, and awaiting Board approval.</b>
         */
        POLICY_DEFAULT_AWAITING_BOARD_APPROVAL(
            "a policy default declared in code, not a specification figure, and with no Board"
                + " approval on file"),

        /**
         * The specification requires a threshold and publishes none, and this engine deliberately
         * declares no default. A caller must supply one.
         */
        NOT_SET_NO_DEFAULT_TAKEN(
            "required by the specification, published nowhere, and deliberately left with no"
                + " default in this engine"),

        /** A limit of this engine's implementation rather than of policy. */
        ENGINE_LIMITATION("a limit of this engine's implementation, not of policy");

        private final String meaning;

        Provenance(String meaning) {
            this.meaning = meaning;
        }

        public String meaning() {
            return meaning;
        }
    }

    public PolicyParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(note, "note");
        if (name.isBlank() || reference.isBlank() || note.isBlank()) {
            throw new IllegalArgumentException(
                "a published policy parameter names itself, cites its source and says what it"
                    + " decides; one of the three arrived blank for " + name);
        }
    }

    /** Whether this value is one nobody has approved — the ones a reader must weigh differently. */
    public boolean requiresBoardAttention() {
        return provenance == Provenance.POLICY_DEFAULT_AWAITING_BOARD_APPROVAL
            || provenance == Provenance.NOT_SET_NO_DEFAULT_TAKEN;
    }

    /**
     * Every parameter governing this register, read off the constants themselves.
     *
     * <p>Read rather than restated: {@code DEEP_DISCOUNT_ACCRETION_SHARE.toPlainString()} cannot
     * drift from the value the gate actually applies, and a report quoting a threshold the engine
     * does not use would be worse than one quoting none.
     */
    public static List<PolicyParameter> governing() {
        return List.of(
            new PolicyParameter(
                "EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE",
                EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE.toPlainString(),
                Provenance.POLICY_DEFAULT_AWAITING_BOARD_APPROVAL,
                "03 § 10.3 · FR-412 · reference case 9",
                "the share of total return arising from accretion at or above which FR-412"
                    + " refuses Tier 3 outright. Neither 03 § 10.3 nor reference case 9 publishes"
                    + " a number — they publish the mechanism (\"the entire return is accretion"
                    + " and the straight-line error compounds with tenor\") and an absolute"
                    + " prohibition. This 0.50 is a policy default declared in"
                    + " EquivalenceTestSubject and stated there to be \"a Board matter and not an"
                    + " implementation detail\". It decides which instruments the refusal catches."
                    + " The zero-coupon limb does not depend on it: that limb tests for the"
                    + " absence of a coupon leg, so the absolute part of FR-412 is absolute"
                    + " wherever this share is set"),
            new PolicyParameter(
                "EquivalenceTestRecord.ANNUAL_WINDOW",
                EquivalenceTestRecord.ANNUAL_WINDOW.toString(),
                Provenance.SPECIFICATION,
                "03 § 10.2 item 3 · invariant TG-1 · control C-10 in 07 § 4",
                "how long a performed equivalence test keeps a Tier 3 population on the"
                    + " shortcut. An out-of-date test demotes the population to Tier 2. A period"
                    + " of one year rather than 365 days, so an annual programme run on financial"
                    + " year-ends does not drift out of date by a day per leap year"),
            new PolicyParameter(
                "EquivalenceTestGate.DEMOTION_TIER",
                EquivalenceTestGate.DEMOTION_TIER.name(),
                Provenance.SPECIFICATION,
                "FR-411 · 03 § 10.2 · 03 § 10.1",
                "what a refused or unevidenced Tier 3 population is measured at instead. Tier 2"
                    + " is more expensive and more correct — a pool EIR under the ACPIR 51 group"
                    + " presumption — which is why missing evidence demotes rather than"
                    + " quarantines, and why STALE_EQUIVALENCE_TEST does not stop the contract"),
            new PolicyParameter(
                "PoolBackTest.QUARTERLY_WINDOW",
                PoolBackTest.QUARTERLY_WINDOW.toString(),
                Provenance.SPECIFICATION,
                "03 § 10.1",
                "how long a performed pool back-test keeps a pool on collective measurement."
                    + " 03 § 10.1 makes the back-test mandatory and quarterly"),
            new PolicyParameter(
                "PoolBackTest materiality threshold",
                "(none)",
                Provenance.NOT_SET_NO_DEFAULT_TAKEN,
                "03 § 10.1",
                "03 § 10.1 requires \"a materiality threshold that, when breached, forces the"
                    + " pool to contract-level measurement\" and publishes no number. No default"
                    + " is taken here, deliberately: a deep-discount share set too low catches"
                    + " more instruments and costs Tier 2 measurement, which is more expensive"
                    + " and more correct, whereas a back-test threshold guessed too high would"
                    + " pass pools that should have been forced to contract level — a default"
                    + " would manufacture the permission it is supposed to test. Every"
                    + " PoolBackTest must therefore carry an approved threshold and an approver"),
            new PolicyParameter(
                "RevolvingProjector supported approximations",
                RevolvingApproximation.FEE_OVER_RENEWAL.name(),
                Provenance.ENGINE_LIMITATION,
                "ACPIR 54 · FR-308 · ACPIR 46(2)(iii)",
                "RevolvingProjector's constructor accepts only FEE_OVER_RENEWAL and refuses"
                    + " EIR_OVER_UTILISATION, which needs a utilisation profile supplied as a"
                    + " schedule through ExternalScheduleProjector. A product whose ACPIR 54"
                    + " election is EIR_OVER_UTILISATION is therefore measured on an"
                    + " approximation nobody elected, and this register reports such an election"
                    + " as unevidenced rather than as an approximation in force"));
    }
}
