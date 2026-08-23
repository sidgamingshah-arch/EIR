package com.crisil.eir.calc.solver;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Everything a solve needs, and nothing about the contract that produced it.
 *
 * <p>The solver never reads contract terms — projection is a separate,
 * independently testable stage (3) and this record is the boundary between them.
 * Two contracts with identical flow vectors must solve identically whatever their
 * product, tier or policy version, because that is what makes a nightly replay
 * reproduce a published figure (invariant DT-1).
 *
 * <p><strong>Sign convention.</strong> {@code f(r) = PV(future flows at r) −
 * target}. Flows on the anchor date are <em>not</em> discounted; they are the
 * target, and including them on both sides would double the inception leg. At
 * initial recognition the target is therefore {@code |net cash flow at inception|}
 * — equal to GCA₀ by invariant IC-1 — which {@link #atInception} derives. For a
 * mid-life re-solve (6.2, a benchmark reset) the anchor is the event date, the
 * vector holds only the remaining flows, and the target is the <em>current</em>
 * gross carrying amount, which the caller must pass explicitly.
 *
 * <p>Both signs work unchanged. An asset's inception leg is negative and its target
 * positive; a liability's inception leg is positive and its target negative
 * ({@link #inceptionTarget} negates either). That symmetry is why the engine needs
 * no liability-specific solver path (11).
 *
 * @param flows      the projected vector; only flows after the anchor are discounted
 * @param target     what those flows must discount to, in the vector's currency
 * @param convention how tau is derived, and hence what units the solved rate is in
 * @param seed       the contractual rate in the convention's units, or {@code null}
 *                   where none is available. A contractual seed converges in 3–4
 *                   iterations on typical instruments, and it is also the reference
 *                   point the multiple-root policy needs (4.4(2))
 * @param tolerance  when to stop; tightened for Tier 1 (FR-406)
 * @param band       the plausible annual effective range used to disambiguate
 *                   multiple roots
 */
public record SolveRequest(
    FlowVector flows,
    Money target,
    TimeConvention convention,
    BigDecimal seed,
    SolverTolerance tolerance,
    PlausibleBand band) {

    public SolveRequest {
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(tolerance, "tolerance");
        Objects.requireNonNull(band, "band");
        if (!target.currency().equals(flows.currency())) {
            throw new IllegalArgumentException("target currency " + target.currency().getCurrencyCode()
                + " does not match vector currency " + flows.currency().getCurrencyCode());
        }
        if (seed != null && seed.compareTo(BigDecimal.ONE.negate()) <= 0) {
            throw new IllegalArgumentException(
                "seed must exceed -100%, got " + seed.toPlainString());
        }
    }

    /** A solve to an explicit target — a mid-life re-solve from the current GCA. */
    public static SolveRequest of(FlowVector flows, Money target, TimeConvention convention, BigDecimal seed) {
        return new SolveRequest(flows, target, convention, seed, SolverTolerance.standard(),
            PlausibleBand.standard());
    }

    /**
     * A solve at initial recognition, taking the target from the vector's own
     * inception leg.
     *
     * <p>Only valid where the vector carries that leg. A vector of remaining flows
     * has nothing dated on its anchor, so this would solve to a target of zero — use
     * {@link #of} with the current gross carrying amount instead.
     */
    public static SolveRequest atInception(FlowVector flows, TimeConvention convention, BigDecimal seed) {
        return of(flows, inceptionTarget(flows), convention, seed);
    }

    /**
     * {@code -(sum of flows dated on the anchor)} — GCA₀ for an asset, and the
     * negative of the amount raised for a liability.
     */
    public static Money inceptionTarget(FlowVector flows) {
        return Discounting.netAtInception(flows).negate();
    }

    /** Whether a contractual rate is available to seed from and disambiguate against. */
    public boolean hasSeed() {
        return seed != null;
    }

    /** {@code max(floor, |target| * factor)} for this request's target (4.2 step 4). */
    public BigDecimal absoluteTolerance() {
        return tolerance.absoluteFor(target);
    }

    /** The same request seeded from a contractual rate. */
    public SolveRequest withSeed(BigDecimal contractualRate) {
        return new SolveRequest(flows, target, convention, contractualRate, tolerance, band);
    }

    /** The same request under a different tolerance — the Tier 1 tightening. */
    public SolveRequest withTolerance(SolverTolerance replacement) {
        return new SolveRequest(flows, target, convention, seed, replacement, band);
    }

    /** The same request under a per-product plausible band. */
    public SolveRequest withBand(PlausibleBand replacement) {
        return new SolveRequest(flows, target, convention, seed, tolerance, replacement);
    }
}
