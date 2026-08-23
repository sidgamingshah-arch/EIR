package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Projector selection: by shape and feature, not by registry order.
 *
 * <p>Order is the caller's to set so that the {@code LMS_AUTHORITATIVE} path can be
 * placed first and win wherever a billed schedule exists (FR-102). Beyond that,
 * order must not decide anything — each shape projector declines terms carrying a
 * feature it does not handle, so adding a projector cannot change what an existing
 * one answers.
 *
 * <p>Where nothing supports the terms the registry raises rather than falling back
 * to a projector that nearly fits. A near fit produces a schedule the lender never
 * billed and a rate solved over it: a plausible number with no trace, which is the
 * failure class this engine exists to refuse. A missing projector is a configuration
 * gap and the contract routes to the exception queue.
 */
class ProjectorRegistryTest {

    @Test
    @DisplayName("the standard registry resolves each derived shape to its own projector")
    void standardRegistryResolvesEachShape() {
        ProjectorRegistry registry = ProjectorRegistry.standard();

        assertThat(registry.select(case1())).isInstanceOf(AnnuityProjector.class);
        assertThat(registry.select(case1().withMoratorium(6, true)))
            .isInstanceOf(MoratoriumProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED)
            .withStepFactor(bd("1.10")))).isInstanceOf(StepScheduleProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.BALLOON, 24, RateType.FIXED)
            .withBalloon(Money.inr("200000")))).isInstanceOf(BalloonProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.INTEREST_ONLY_BULLET, 60, RateType.FIXED)))
            .isInstanceOf(InterestOnlyBulletProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.BULLET, 60, RateType.FIXED)))
            .isInstanceOf(BulletProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.DISCOUNT_INSTRUMENT, 12, RateType.FIXED)))
            .isInstanceOf(DiscountInstrumentProjector.class);
        assertThat(registry.select(shaped(ScheduleShape.REVOLVING, 12, RateType.FLOATING)))
            .isInstanceOf(RevolvingProjector.class);
    }

    @Test
    @DisplayName("the moratorium projector wins whatever order the registry is built in")
    void orderIsNotTheMechanismThatKeepsProjectorsApart() {
        // The annuity projector declines a moratorium, so both orders answer the same.
        // Ordering as a tie-break of last resort is fine; ordering as the mechanism that
        // keeps two projectors apart is not.
        ContractTerms moratorium = case1().withMoratorium(6, true);
        ProjectorRegistry annuityFirst = ProjectorRegistry.of(
            new AnnuityProjector(), new MoratoriumProjector());
        ProjectorRegistry moratoriumFirst = ProjectorRegistry.of(
            new MoratoriumProjector(), new AnnuityProjector());

        assertThat(annuityFirst.select(moratorium)).isInstanceOf(MoratoriumProjector.class);
        assertThat(moratoriumFirst.select(moratorium)).isInstanceOf(MoratoriumProjector.class);
        assertThat(annuityFirst.select(case1())).isInstanceOf(AnnuityProjector.class);
        assertThat(moratoriumFirst.select(case1())).isInstanceOf(AnnuityProjector.class);
    }

    @Test
    @DisplayName("a prepended projector is consulted first — the LMS_AUTHORITATIVE path")
    void prependPlacesTheBilledScheduleFirst() {
        // This is how the billed-schedule path and the B5.4.4 election are layered onto
        // an otherwise shared registry: both are per-contract or per-product
        // configurations, not shapes.
        ExternalScheduleProjector billed = new ExternalScheduleProjector(List.of(
            Instalment.of(FIRST_DUE, 1, Money.inr("500000")),
            Instalment.of(FIRST_DUE.plusMonths(1), 2, Money.inr("530000"))));
        ProjectorRegistry registry = ProjectorRegistry.standard().prepend(billed);

        assertThat(registry.select(case1())).isSameAs(billed);
        assertThat(registry.projectors()).hasSize(ProjectorRegistry.standard().projectors().size() + 1);
        assertThat(registry.projectors().get(0)).isSameAs(billed);
        // Prepending returns a new registry: the shared one is unchanged for every other
        // contract in the run.
        assertThat(ProjectorRegistry.standard().select(case1())).isInstanceOf(AnnuityProjector.class);
    }

    @Test
    @DisplayName("an unsupported shape raises, naming the shape and the registered projectors")
    void unsupportedShapeRaises() {
        ContractTerms structured = shaped(ScheduleShape.STRUCTURED, 24, RateType.FIXED);

        assertThatThrownBy(() -> ProjectorRegistry.standard().select(structured))
            .isInstanceOf(UnsupportedScheduleShapeException.class)
            .hasMessageContaining("no projector supports schedule shape STRUCTURED")
            .hasMessageContaining("AnnuityProjector");
        assertThatThrownBy(() -> ProjectorRegistry.standard().project(structured, case1Fees()))
            .isInstanceOf(UnsupportedScheduleShapeException.class)
            .satisfies(thrown -> assertThat(((UnsupportedScheduleShapeException) thrown).shape())
                .isEqualTo(ScheduleShape.STRUCTURED));
    }

    @Test
    @DisplayName("a step schedule with no factor is not projected as a flat annuity")
    void aMissingFeatureIsAConfigurationGapAndNotADefault() {
        // A STEP_UP with no factor could be projected as a level annuity, and the result
        // would be a schedule the lender never billed. The registry raises instead.
        ContractTerms stepWithoutFactor = shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED);

        assertThat(new StepScheduleProjector().supports(stepWithoutFactor)).isFalse();
        assertThatThrownBy(() -> ProjectorRegistry.standard().select(stepWithoutFactor))
            .isInstanceOf(UnsupportedScheduleShapeException.class);
        // Likewise a balloon shape with no terminal lump sum: nothing to size the
        // instalments against.
        assertThat(new BalloonProjector().supports(shaped(ScheduleShape.BALLOON, 24, RateType.FIXED)))
            .isFalse();
    }

    @Test
    @DisplayName("an empty registry cannot be constructed")
    void anEmptyRegistryIsRefused() {
        assertThatThrownBy(() -> ProjectorRegistry.of(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a registry with no projectors cannot project anything");
    }

    @Test
    @DisplayName("the standard registry excludes the projectors that need instrument data")
    void standardRegistryOmitsWhatItCannotConfigure() {
        // ExternalScheduleProjector needs the billed schedule, TranchedProjector the draw
        // schedule, and RepricingShortcutProjector is a per-product election over another
        // projector rather than a shape. Each is added with prepend, per contract.
        assertThat(ProjectorRegistry.standard().projectors())
            .noneMatch(projector -> projector instanceof ExternalScheduleProjector
                || projector instanceof TranchedProjector
                || projector instanceof RepricingShortcutProjector);
        assertThatThrownBy(() -> ProjectorRegistry.standard()
            .select(shaped(ScheduleShape.TRANCHED, 24, RateType.FIXED)))
            .isInstanceOf(UnsupportedScheduleShapeException.class);
    }

    @Test
    @DisplayName("select and project agree on which projector ran")
    void projectDelegatesToTheSelectedProjector() {
        ProjectorRegistry registry = ProjectorRegistry.standard();

        ProjectionResult viaRegistry = registry.project(case1(), case1Fees());
        ProjectionResult viaProjector = registry.select(case1()).project(case1(), case1Fees());

        assertThat(viaRegistry.contractual()).isEqualTo(viaProjector.contractual());
        assertThat(viaRegistry.initialCarryingAmount()).isEqualTo(viaProjector.initialCarryingAmount());
    }
}
