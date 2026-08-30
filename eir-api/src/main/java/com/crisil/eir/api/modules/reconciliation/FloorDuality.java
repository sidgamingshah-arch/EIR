package com.crisil.eir.api.modules.reconciliation;

import com.crisil.eir.api.EirService;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.calc.amort.FloorApplication;
import com.crisil.eir.calc.amort.FloorBasis;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Stage;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PF-1, the fourth of 06 § 7's reconciliations: the pre-floor and post-floor provisions side by
 * side, one row per exposure (FR-609, ACPIR 90, 03 § 7.5).
 *
 * <h2>What this book can and cannot support, said before any figure is read</h2>
 *
 * <p><b>No regulatory floor is supplied, so both columns hold the same figure.</b> The engine
 * consumes an accounting ECL measured at the EIR it published — {@code OpeningState.allowance} —
 * and 00 § 4 puts the floor itself outside its scope; this in-memory book carries no ACPIR 90 floor
 * for any product category, so the floor on every row here is nil, the reported provision is the
 * accounting figure unchanged, and the divergence is nil. <b>A nil-against-nil duality reads as
 * tied on every run ever made</b>, which is exactly why {@code RunClose} refuses to synthesise the
 * pre-/post-floor tie and why every response built from this class carries the fact as a caveat
 * rather than as a green row.
 *
 * <p><b>PF-1 as published here is asserted over a pair this report just computed, so it cannot go
 * red.</b> That is worth being precise about, because it is a property of where the pair comes from
 * and not of the invariant. {@link FloorApplication#preFloorRetained} is a real control the moment
 * the reported provision is read back from somewhere it was <em>stored</em> — its own javadoc says
 * so: "a caller that persists a provision has to be able to assert the same thing about the pair of
 * columns it wrote". Nothing in this engine persists a reported provision yet, so the pair on each
 * row is {@link FloorApplication#apply}'s own output and the assertion is against the arithmetic
 * that produced it. Labelled, not hidden.
 *
 * <p><b>PF-2 is withheld unless the caller states the basis, and is never invented.</b> The floor
 * basis — account level or portfolio level — is an attribute of how the ECL engine applied the
 * floor, and this book records none. Defaulting it to {@code ACCOUNT} would make
 * {@link FloorApplication#basisPermitted} pass on every row in every stage, which is a control
 * asserted over a fabricated input: worse than an absent one, because it is signed. So PF-2 appears
 * only when {@code ?basis=} states it, and then it is a control that can and does fail — a Stage 3
 * exposure floored on a {@code PORTFOLIO} basis breaks it, with the reported provision as the
 * deviation, because a pooled floor averages the shortfall on accounts that have one against
 * accounts that do not.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7.
 */
public final class FloorDuality {

    /**
     * The basis {@link FloorApplication#apply} is reached with when the caller states none.
     *
     * <p><b>It changes no figure, and it must not reach a response.</b> {@code apply} needs a basis
     * to build a record; the reported provision is the greater of the accounting figure and the
     * floor regardless of it, and PF-1 is a statement about that pair only. What the basis governs
     * is PF-2. But the record {@code apply} returns carries this constant on {@code basis()}, inside
     * {@code describe()} and as a fully-formed PF-2 result on {@code invariants()} — so
     * {@link Exposure} lifts the figures off it and holds the <em>stated</em> basis instead, and
     * this constant does not leave the file. Getting that wrong published
     * {@code "basisApplied":"ACCOUNT"} beside {@code "floorBasisStated":false}; the test that now
     * catches it is {@code thePlaceholderBasisDoesNotLeak}.
     */
    private static final FloorBasis UNSTATED = FloorBasis.ACCOUNT;

    private final EirService.PublishedFigures figures;
    private final FloorBasis statedBasis;
    private final List<Exposure> exposures;
    private final List<String> exposuresWithoutState;

    private FloorDuality(
        EirService.PublishedFigures figures,
        FloorBasis statedBasis,
        List<Exposure> exposures,
        List<String> exposuresWithoutState) {
        this.figures = figures;
        this.statedBasis = statedBasis;
        this.exposures = exposures;
        this.exposuresWithoutState = exposuresWithoutState;
    }

    /**
     * One exposure's duality: the figures the evaluator published, and nothing it inferred.
     *
     * <p><b>Why the {@link FloorApplication} itself is not a field.</b> Where the caller states no
     * basis, {@link FloorApplication#apply} still has to be reached with one, and the record it
     * returns then carries {@link #UNSTATED} on {@code basis()}, inside {@code describe()}, and as
     * a fully-formed PF-2 result on {@code invariants()}. Holding that record and letting readers
     * help themselves is how a placeholder reaches a response, and it did: the first version of
     * this class published {@code "basisApplied":"ACCOUNT"} on every row of a response that also
     * said {@code "floorBasisStated":false} and carried a caveat calling a defaulted basis a
     * control asserted over a fabricated input. So the figures are lifted off the application here
     * and the placeholder does not leave this file. {@code basisStated} is the basis the
     * <em>caller</em> stated, or null.
     *
     * @param contractId            the exposure
     * @param stage                 the stage the ECL engine put it in
     * @param eclEngineVersion      which ECL model measured the pre-floor figure; 04 § 2.9 requires
     *                              it to travel with the figure, and a provision whose model is
     *                              unnamed is one no replay can reproduce
     * @param preFloorAccountingEcl the EIR-derived figure, retained verbatim
     * @param regulatoryFloor       the ACPIR 90 floor; nil throughout on this book
     * @param reportedProvision     what is reported: the greater of the two, from the evaluator
     * @param flooredBy             the ACPIR 90 divergence, from the evaluator
     * @param floorBinds            whether the floor raised the reported figure
     * @param basisStated           the basis the caller stated, or {@code null}
     * @param preFloorRetained      PF-1 over the pair; see the class javadoc on why it cannot fail
     * @param basisPermitted        PF-2, or {@code null} where the caller stated no basis
     */
    public record Exposure(
        String contractId,
        Stage stage,
        String eclEngineVersion,
        Money preFloorAccountingEcl,
        Money regulatoryFloor,
        Money reportedProvision,
        Money flooredBy,
        boolean floorBinds,
        FloorBasis basisStated,
        InvariantResult preFloorRetained,
        InvariantResult basisPermitted) {

        public Exposure {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(preFloorAccountingEcl, "preFloorAccountingEcl");
            Objects.requireNonNull(regulatoryFloor, "regulatoryFloor");
            Objects.requireNonNull(reportedProvision, "reportedProvision");
            Objects.requireNonNull(flooredBy, "flooredBy");
            Objects.requireNonNull(preFloorRetained, "preFloorRetained");
        }

        /** Whether a floor was supplied for this exposure at all. */
        public boolean regulatoryFloorSupplied() {
            return !regulatoryFloor.isZero();
        }
    }

    /**
     * Resolve the duality for the period these figures were published for.
     *
     * @param figures     the run's working papers and the read ports it was assembled with
     * @param statedBasis the basis the caller says the floor was applied on, or empty; empty
     *                    withholds PF-2 rather than defaulting it
     */
    public static FloorDuality over(
        EirService.PublishedFigures figures, Optional<FloorBasis> statedBasis) {
        Objects.requireNonNull(figures, "figures");
        Objects.requireNonNull(statedBasis, "statedBasis");
        RunRequest request = figures.request();
        FloorBasis basis = statedBasis.orElse(null);

        List<Exposure> exposures = new ArrayList<>();
        List<String> withoutState = new ArrayList<>();

        // The population, not the exposures that happen to carry an allowance. A duality report
        // over the subset with a provision is a report that cannot show a stage-1 exposure whose
        // allowance is nil, and — worse — silently drops any exposure the ECL feed did not reach.
        for (ContractResult result : figures.aggregate().results()) {
            Optional<ContractStateSource.OpeningState> state =
                request.contractState().openingState(result.contractId(), request.boundary());
            if (state.isEmpty()) {
                // The contract master does not carry this one at the boundary — C-0003's condition
                // before it is repaired. Named rather than skipped: an exposure absent from a
                // provision report is absent from both of its columns and from every total, and
                // the report would foot perfectly without it.
                withoutState.add(result.contractId());
                continue;
            }
            ContractStateSource.OpeningState opening = state.get();
            Money accountingEcl = opening.allowance();
            Money regulatoryFloor = Money.zero(accountingEcl.currency());

            FloorApplication application = FloorApplication.apply(
                accountingEcl, regulatoryFloor,
                basis == null ? UNSTATED : basis, opening.stage());

            // Both from the evaluator's own statics, and the reported provision is read off the
            // application rather than re-derived as a second max(): that comparison is the whole
            // of what FloorApplication does, and a report that repeated it would be the second
            // place the floor is applied.
            InvariantResult preFloorRetained = FloorApplication.preFloorRetained(
                accountingEcl, regulatoryFloor, application.reportedProvision());
            InvariantResult basisPermitted = basis == null
                ? null
                : FloorApplication.basisPermitted(
                    basis, opening.stage(), application.reportedProvision());

            exposures.add(new Exposure(
                result.contractId(), opening.stage(), opening.eclEngineVersion(),
                application.accountingEcl(), application.regulatoryFloor(),
                application.reportedProvision(), application.flooredBy(),
                application.floorBinds(), basis, preFloorRetained, basisPermitted));
        }

        return new FloorDuality(
            figures, basis, List.copyOf(exposures), List.copyOf(withoutState));
    }

    /** The run's published figures, for the population counts every response carries. */
    public EirService.PublishedFigures figures() {
        return figures;
    }

    /** One row per exposure the contract master answered for, in population order. */
    public List<Exposure> exposures() {
        return exposures;
    }

    /**
     * The contracts in the population the master carries no state for at this boundary.
     *
     * <p>Published, because a provision report is a population statement: these exposures have no
     * pre-floor figure, no post-floor figure and no row, and every total below foots without them.
     */
    public List<String> exposuresWithoutState() {
        return exposuresWithoutState;
    }

    /** Whether the caller stated a floor basis, and therefore whether PF-2 was asserted. */
    public boolean basisStated() {
        return statedBasis != null;
    }

    /** The basis the caller stated, or {@code null}. */
    public FloorBasis statedBasis() {
        return statedBasis;
    }

    /**
     * The currency these exposures are denominated in.
     *
     * <p>Read off the population being summed rather than assumed to be INR. Every total below
     * seeds with it, because {@code Money.plus} refuses to add across currencies — correctly — and
     * a total seeded with the wrong one turns a mixed-currency book into a 500 on a report instead
     * of a report. INR only where there is nothing to sum, so that an empty population still
     * produces a well-formed answer rather than throwing on the way to saying it is empty.
     */
    public Currency currency() {
        return exposures.isEmpty()
            ? Money.INR
            : exposures.getFirst().preFloorAccountingEcl().currency();
    }

    /**
     * Whether any exposure carries an ACPIR 90 floor at all.
     *
     * <p><b>Derived, never asserted by the renderer.</b> This is false on this book because the
     * floor is nil on every row, and the response's "the two columns are equal because nothing was
     * floored" caveat hangs off it. A renderer that hardcoded {@code false} would keep saying so —
     * and keep printing the caveat — on the first period a floor is actually supplied, while the
     * columns beside it diverged.
     */
    public boolean regulatoryFloorSupplied() {
        for (Exposure exposure : exposures) {
            if (exposure.regulatoryFloorSupplied()) {
                return true;
            }
        }
        return false;
    }

    /** The pre-floor total: the accounting ECL measured at the EIR, summed. */
    public Money totalAccountingEcl() {
        Money total = Money.zero(currency());
        for (Exposure exposure : exposures) {
            total = total.plus(exposure.preFloorAccountingEcl());
        }
        return total;
    }

    /** The post-floor total: what is reported, summed. */
    public Money totalReportedProvision() {
        Money total = Money.zero(currency());
        for (Exposure exposure : exposures) {
            total = total.plus(exposure.reportedProvision());
        }
        return total;
    }

    /**
     * The ACPIR 90 divergence, summed from each exposure's own published {@code flooredBy()}.
     *
     * <p>Never differenced from the two totals above. The floor is applied exposure by exposure, so
     * a difference of totals is a portfolio-level floor arrived at by accident — which is the exact
     * measurement PF-2 exists to forbid.
     */
    public Money totalFlooredBy() {
        Money total = Money.zero(currency());
        for (Exposure exposure : exposures) {
            total = total.plus(exposure.flooredBy());
        }
        return total;
    }

    /** How many exposures the floor actually raised. Nil on this book, and labelled as such. */
    public int bindingCount() {
        int binding = 0;
        for (Exposure exposure : exposures) {
            if (exposure.floorBinds()) {
                binding++;
            }
        }
        return binding;
    }

    /** Every published PF-1 and PF-2 result, for a breach count that does not double-report. */
    public List<InvariantResult> results() {
        List<InvariantResult> results = new ArrayList<>(exposures.size() * 2);
        for (Exposure exposure : exposures) {
            results.add(exposure.preFloorRetained());
            if (exposure.basisPermitted() != null) {
                results.add(exposure.basisPermitted());
            }
        }
        return List.copyOf(results);
    }

    /** The published results that failed. */
    public List<InvariantResult> breaches() {
        return results().stream().filter(result -> !result.satisfied()).toList();
    }

    /**
     * A basis named on a request, or empty where none was.
     *
     * @throws IllegalArgumentException where the text names no basis this engine has; the caller
     *     is told which two exist rather than being given a default, because a defaulted basis is
     *     the fabricated input the class javadoc refuses
     */
    public static Optional<FloorBasis> basisNamed(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (FloorBasis candidate : FloorBasis.values()) {
            if (candidate.name().equalsIgnoreCase(raw.strip())) {
                return Optional.of(candidate);
            }
        }
        throw new IllegalArgumentException("'basis' names no ACPIR 90 floor basis: got '"
            + raw.strip() + "', and the two are ACCOUNT and PORTFOLIO");
    }
}
