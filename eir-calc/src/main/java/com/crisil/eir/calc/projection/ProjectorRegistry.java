package com.crisil.eir.calc.projection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Selects the projector for a contract: the first registered one that supports the
 * terms.
 *
 * <p>Order matters and is the caller's to set, so the {@code LMS_AUTHORITATIVE}
 * path can be placed first and win wherever a billed schedule exists (FR-102).
 * Beyond that, order should not decide anything: each shape projector declines
 * terms carrying a feature it does not handle, so a moratorium goes to the
 * moratorium projector whether or not the annuity projector was registered before
 * it. Ordering as a tie-break of last resort is fine; ordering as the mechanism
 * that keeps two projectors apart is not, because adding a projector then changes
 * what an existing one answers.
 *
 * <p>Where nothing supports the terms the registry raises
 * {@link UnsupportedScheduleShapeException} naming the shape. It never falls back
 * to a projector that nearly fits: that would produce a schedule the lender never
 * billed and a rate solved over it — a plausible number with no trace, which is the
 * failure class this engine exists to refuse. A missing projector is a
 * configuration gap and the contract routes to the exception queue.
 */
public final class ProjectorRegistry {

    private final List<CashflowProjector> projectors;

    private ProjectorRegistry(List<CashflowProjector> projectors) {
        if (projectors.isEmpty()) {
            throw new IllegalArgumentException("a registry with no projectors cannot project anything");
        }
        for (CashflowProjector projector : projectors) {
            Objects.requireNonNull(projector, "projector");
        }
        this.projectors = List.copyOf(projectors);
    }

    public static ProjectorRegistry of(List<CashflowProjector> projectors) {
        return new ProjectorRegistry(Objects.requireNonNull(projectors, "projectors"));
    }

    public static ProjectorRegistry of(CashflowProjector... projectors) {
        return new ProjectorRegistry(Arrays.asList(Objects.requireNonNull(projectors, "projectors")));
    }

    /**
     * The derived-schedule projectors, in a workable order.
     *
     * <p>Excludes the two that cannot be configured without instrument data —
     * {@link ExternalScheduleProjector} needs the billed schedule and
     * {@link TranchedProjector} needs the draw schedule — and
     * {@link RepricingShortcutProjector}, which is a per-product election over
     * another projector rather than a shape. Add them per contract or per product
     * with {@link #prepend}.
     *
     * <p>Every projector here derives its schedule, which in production is the
     * second choice: prefer the billed schedule wherever the lending system can
     * supply one.
     */
    public static ProjectorRegistry standard() {
        return of(
            new MoratoriumProjector(),
            new AnnuityProjector(),
            new StepScheduleProjector(),
            new BalloonProjector(),
            new InterestOnlyBulletProjector(),
            new BulletProjector(),
            new DiscountInstrumentProjector(),
            new RevolvingProjector());
    }

    /**
     * A registry with {@code first} consulted before everything already here.
     *
     * <p>This is how the {@code LMS_AUTHORITATIVE} path and the B5.4.4 election are
     * layered on: both are per-contract or per-product configurations of an
     * otherwise shared registry.
     */
    public ProjectorRegistry prepend(CashflowProjector first) {
        List<CashflowProjector> combined = new ArrayList<>();
        combined.add(Objects.requireNonNull(first, "first"));
        combined.addAll(projectors);
        return new ProjectorRegistry(combined);
    }

    /** The registered projectors, in consultation order. */
    public List<CashflowProjector> projectors() {
        return projectors;
    }

    /**
     * The first projector that supports these terms.
     *
     * @throws UnsupportedScheduleShapeException where none does
     */
    public CashflowProjector select(ContractTerms terms) {
        Objects.requireNonNull(terms, "terms");
        for (CashflowProjector projector : projectors) {
            if (projector.supports(terms)) {
                return projector;
            }
        }
        throw new UnsupportedScheduleShapeException(terms, labels());
    }

    /** Selects and projects. */
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        return select(terms).project(terms, Objects.requireNonNull(fees, "fees"));
    }

    private List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (CashflowProjector projector : projectors) {
            labels.add(projector.label());
        }
        return labels;
    }
}
