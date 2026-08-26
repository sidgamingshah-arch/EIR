package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Stage;
import java.util.List;
import java.util.Objects;

/**
 * Pre-floor and post-floor as two reported figures, not one figure and an intermediate
 * (FR-609, ACPIR 90, 03 § 7.5).
 *
 * <p><b>The requirement, and the implementation it forbids.</b> Where a prudential floor binds,
 * the EIR-derived accounting number and the reported provision diverge. 03 § 7.5 states the
 * ordering as: compute the accounting number at the EIR, apply the floor, report <em>both</em>.
 * The forbidden implementation is the natural one — compute, floor, store the result — after which
 * the number the EIR produced does not exist anywhere. Nothing then fails: the reported provision
 * is correct, the ledger balances, and the only thing lost is the ability to say by how much
 * measurement and reporting differ, which is the disclosure and the reconciliation.
 *
 * <p>So this type takes the pre-floor figure and returns it, unchanged, alongside the reported
 * one. There is no accessor that gives only the reported number without the other in the same
 * record, and {@link #flooredBy()} publishes the divergence as a figure in its own right rather
 * than leaving it to be re-derived by whoever needs it.
 *
 * <p><b>Why ECL appears here at all,</b> given that 00 § 4 puts impairment measurement out of
 * scope. The accounting ECL is consumed, not computed: it arrives as a versioned input measured
 * by the risk model at the EIR this engine published. What is in scope is the floor
 * <em>application</em>, because the ordering it has to respect is a property of the EIR the engine
 * owns, and because the pre-floor figure that must survive it is the engine's output.
 *
 * <p><b>Two invariants, because there are two distinct failures.</b> {@link InvariantId#PF_1}
 * breaks when the pre-floor number is lost or the floor moved the reported figure to somewhere
 * other than the greater of the two, and the remedy is to retain it. {@link InvariantId#PF_2}
 * breaks when a Stage 3 exposure was floored on a portfolio basis, and the remedy is to recompute
 * it — a pooled floor averages the shortfall on accounts that have one against accounts that do
 * not, which understates the floor on exactly the exposures where it binds hardest. One id
 * carrying both would report whichever came first and lose the other, since
 * {@link InvariantResult#conjunction} keeps only the first breach's deviation among results
 * sharing an id.
 *
 * @param accountingEcl     the pre-floor, EIR-derived figure, retained verbatim
 * @param regulatoryFloor   the ACPIR 90 floor for the product category
 * @param reportedProvision what is reported: the greater of the two
 * @param basis             the level the floor was applied at
 * @param stage             the stage the exposure was in
 * @param invariants        PF-1 always; PF-2 always, since the basis rule applies in every stage
 */
public record FloorApplication(
    Money accountingEcl,
    Money regulatoryFloor,
    Money reportedProvision,
    FloorBasis basis,
    Stage stage,
    List<InvariantResult> invariants) {

    public FloorApplication {
        Objects.requireNonNull(accountingEcl, "accountingEcl");
        Objects.requireNonNull(regulatoryFloor, "regulatoryFloor");
        Objects.requireNonNull(reportedProvision, "reportedProvision");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(stage, "stage");
        invariants = List.copyOf(Objects.requireNonNull(invariants, "invariants"));
    }

    /**
     * Apply the floor, keeping both figures.
     *
     * @param accountingEcl   the pre-floor figure, measured at the EIR
     * @param regulatoryFloor the ACPIR 90 floor for this exposure's product category
     * @param basis           the level the floor was applied at, as the caller applied it
     * @param stage           the exposure's stage
     */
    public static FloorApplication apply(
        Money accountingEcl, Money regulatoryFloor, FloorBasis basis, Stage stage) {
        Objects.requireNonNull(accountingEcl, "accountingEcl");
        Objects.requireNonNull(regulatoryFloor, "regulatoryFloor");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(stage, "stage");

        // A floor raises or does nothing. Comparison rather than arithmetic, and no rounding on
        // the way through: a floor that binds by a paise binds.
        Money reported = regulatoryFloor.compareTo(accountingEcl) > 0
            ? regulatoryFloor
            : accountingEcl;

        return new FloorApplication(accountingEcl, regulatoryFloor, reported, basis, stage,
            List.of(
                preFloorRetained(accountingEcl, regulatoryFloor, reported),
                basisPermitted(basis, stage, reported)));
    }

    /**
     * Invariant PF-1: the figure handed in comes back, and the reported figure is the greater of
     * it and the floor.
     *
     * <p>Public and separately callable because the requirement is on the <em>reporting</em>, not
     * only on this type: a caller that persists a provision has to be able to assert the same
     * thing about the pair of columns it wrote, and a check reachable only through the type that
     * computed the pair would not be assertable there.
     */
    public static InvariantResult preFloorRetained(
        Money accountingEcl, Money regulatoryFloor, Money reportedProvision) {
        Money expected = regulatoryFloor.compareTo(accountingEcl) > 0
            ? regulatoryFloor
            : accountingEcl;
        Money deviation = reportedProvision.minus(expected);
        boolean binds = regulatoryFloor.compareTo(accountingEcl) > 0;
        String detail = "accounting ECL at the EIR " + accountingEcl.atPresentationScale()
            + ", ACPIR 90 floor " + regulatoryFloor.atPresentationScale()
            + ", reported " + reportedProvision.atPresentationScale()
            + (binds ? " — the floor binds, and both figures are retained"
                : " — the floor does not bind");
        if (deviation.signum() == 0) {
            return InvariantResult.pass(InvariantId.PF_1, detail);
        }
        return InvariantResult.fail(InvariantId.PF_1,
            detail + " — the reported provision is neither the accounting figure nor the floor,"
                + " so one of the two has been overwritten",
            deviation.amount());
    }

    /**
     * Invariant PF-2: a Stage 3 exposure is floored at account level.
     *
     * <p>The deviation is the provision floored on the wrong basis, rather than a count. A close
     * reading this needs to know how much has to be restated, and one breach of ten rupees and
     * one of ten crore are not the same finding.
     */
    public static InvariantResult basisPermitted(
        FloorBasis basis, Stage stage, Money reportedProvision) {
        String detail = "ACPIR 90 floor applied on a " + basis + " basis in " + stage;
        if (basis.isPermittedFor(stage)) {
            return InvariantResult.pass(InvariantId.PF_2, detail);
        }
        return InvariantResult.fail(InvariantId.PF_2,
            detail + ", where " + FloorBasis.mandatoryFor(stage) + " is mandatory; a pooled floor"
                + " averages the shortfall on accounts that have one against accounts that do"
                + " not, understating it on exactly the exposures where it binds hardest",
            reportedProvision.amount());
    }

    /** Whether the floor raised the reported provision above the accounting figure. */
    public boolean floorBinds() {
        return regulatoryFloor.compareTo(accountingEcl) > 0;
    }

    /**
     * The ACPIR 90 divergence: reported less accounting, nil where the floor does not bind.
     *
     * <p>Published as a figure rather than left to be re-derived. This is the number the
     * disclosure is about, and a quantity that every consumer computes for itself is a quantity
     * two of them will compute differently.
     */
    public Money flooredBy() {
        return floorBinds()
            ? reportedProvision.minus(accountingEcl)
            : Money.zero(accountingEcl.currency());
    }

    /** The invariants that failed; empty on a clean application. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /** A one-line duality statement, at presentation scale. */
    public String describe() {
        return "accounting " + accountingEcl.atPresentationScale()
            + " | floor " + regulatoryFloor.atPresentationScale()
            + " | reported " + reportedProvision.atPresentationScale()
            + (floorBinds() ? " (floored by " + flooredBy().atPresentationScale() + ")" : "")
            + " on a " + basis + " basis in " + stage;
    }
}
