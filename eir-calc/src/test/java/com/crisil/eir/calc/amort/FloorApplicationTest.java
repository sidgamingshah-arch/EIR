package com.crisil.eir.calc.amort;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pre-floor and post-floor duality (FR-609, ACPIR 90, 03 § 7.5).
 *
 * <p><b>Where the figures come from.</b> Reference case 5's position — gross carrying amount
 * 528,407.32 at the month-13 opening, with a 40% lifetime ECL of 211,362.93 as a ledger holds it.
 * Floor amounts are derived by hand as percentages of that balance:
 *
 * <pre>
 *   accounting ECL, 40%   211,362.93
 *   a floor at 15%         79,261.098   (0.15 x 528,407.32)  — does not bind
 *   a floor at 60%        317,044.392   (0.60 x 528,407.32)  — binds
 *   divergence            105,681.462   (317,044.392 − 211,362.93)
 * </pre>
 *
 * <p>The percentages are illustrative and deliberately not asserted as ACPIR 90's own. The
 * Directions set them per product category and FR-106 makes the product tag aligned to the ACPIR
 * 82 floor categories and <em>shared with the provisioning engine</em> — so the floor arrives here
 * as an amount that engine computed, and a copy of its rate table in this module would be a second
 * place for the rates to be wrong.
 */
class FloorApplicationTest {

    private static final Money GCA = Money.inr("528407.32");
    private static final Money ACCOUNTING_ECL = Money.inr("211362.93");
    private static final Money FLOOR_15_PCT = Money.inr("79261.098");
    private static final Money FLOOR_60_PCT = Money.inr("317044.392");

    @Nested
    @DisplayName("both figures survive, whether the floor binds or not")
    class Duality {

        @Test
        @DisplayName("a binding floor raises the reported provision and does not touch the other")
        void bindingFloor() {
            FloorApplication applied = FloorApplication.apply(
                ACCOUNTING_ECL, FLOOR_60_PCT, FloorBasis.ACCOUNT, Stage.STAGE_3);

            assertThat(applied.floorBinds()).isTrue();
            assertThat(applied.reportedProvision()).isEqualTo(FLOOR_60_PCT);
            assertThat(applied.accountingEcl())
                .as("the EIR-derived figure, unchanged — this is the whole of FR-609")
                .isEqualTo(ACCOUNTING_ECL);
            assertThat(applied.flooredBy())
                .as("317,044.392 − 211,362.93")
                .isEqualTo(Money.inr("105681.462"));
            assertThat(applied.breaches()).isEmpty();
        }

        @Test
        @DisplayName("a floor below the accounting figure changes nothing")
        void nonBindingFloor() {
            FloorApplication applied = FloorApplication.apply(
                ACCOUNTING_ECL, FLOOR_15_PCT, FloorBasis.ACCOUNT, Stage.STAGE_3);

            assertThat(applied.floorBinds()).isFalse();
            assertThat(applied.reportedProvision()).isEqualTo(ACCOUNTING_ECL);
            assertThat(applied.flooredBy())
                .as("nil, not the negative shortfall — a floor that lowered a provision"
                    + " would not be a floor")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(applied.breaches()).isEmpty();
        }

        @Test
        @DisplayName("a floor exactly equal to the accounting figure does not bind")
        void exactlyEqual() {
            // The boundary, and it matters for the disclosure rather than the number: reporting a
            // divergence of nil as "the floor binds" would put an exposure on a schedule of
            // floored accounts that has nothing to explain.
            FloorApplication applied = FloorApplication.apply(
                ACCOUNTING_ECL, ACCOUNTING_ECL, FloorBasis.ACCOUNT, Stage.STAGE_3);
            assertThat(applied.floorBinds()).isFalse();
            assertThat(applied.reportedProvision()).isEqualTo(ACCOUNTING_ECL);
        }

        @Test
        @DisplayName("a floor binding by a paise binds")
        void bindsByAPaise() {
            // No rounding on the way through. A tolerance here would be a tolerance on a
            // regulatory minimum.
            FloorApplication applied = FloorApplication.apply(
                ACCOUNTING_ECL, ACCOUNTING_ECL.plus(Money.inr("0.01")),
                FloorBasis.ACCOUNT, Stage.STAGE_3);
            assertThat(applied.floorBinds()).isTrue();
            assertThat(applied.flooredBy()).isEqualTo(Money.inr("0.01"));
        }
    }

    @Nested
    @DisplayName("PF-1: the pre-floor figure is retained")
    class PreFloorRetained {

        @Test
        @DisplayName("passes when the reported figure is the greater of the two")
        void passes() {
            InvariantResult result = FloorApplication.apply(
                ACCOUNTING_ECL, FLOOR_60_PCT, FloorBasis.ACCOUNT, Stage.STAGE_3)
                .invariants().getFirst();
            assertThat(result.id()).isEqualTo(InvariantId.PF_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("the floor binds, and both figures are retained");
        }

        @Test
        @DisplayName("fails on a reported figure that is neither the accounting number nor the floor")
        void catchesAnOverwrite() {
            // Callable on a pair of persisted columns, which is why it is public and static. The
            // failure it is for is not reachable through apply(): compute, floor, store one
            // number, and later read back a provision that is 300,000 against an accounting
            // figure of 211,362.93 and a floor of 317,044.392. Nothing else notices — the ledger
            // balances and the provision is plausible.
            InvariantResult result = FloorApplication.preFloorRetained(
                ACCOUNTING_ECL, FLOOR_60_PCT, Money.inr("300000.00"));

            assertThat(result.id()).isEqualTo(InvariantId.PF_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("300,000.00 − 317,044.392, the distance from where the reported figure belongs")
                .isEqualByComparingTo(new BigDecimal("-17044.392"));
            assertThat(result.detail()).contains("one of the two has been overwritten");
        }
    }

    @Nested
    @DisplayName("PF-2: Stage 3 is floored at account level (ACPIR 90)")
    class BasisRule {

        @Test
        @DisplayName("a Stage 3 exposure floored on a portfolio basis is a breach")
        void pooledStageThreeIsRefused() {
            FloorApplication applied = FloorApplication.apply(
                ACCOUNTING_ECL, FLOOR_60_PCT, FloorBasis.PORTFOLIO, Stage.STAGE_3);

            assertThat(applied.breaches()).hasSize(1);
            InvariantResult breach = applied.breaches().getFirst();
            assertThat(breach.id()).isEqualTo(InvariantId.PF_2);
            assertThat(breach.deviation())
                .as("the provision floored on the wrong basis — a close needs to know how much"
                    + " has to be restated, not that one exposure was wrong")
                .isEqualByComparingTo(new BigDecimal("317044.392"));
            assertThat(breach.detail()).contains("where ACCOUNT is mandatory");
        }

        @Test
        @DisplayName("Stages 1 and 2 may be floored either way")
        void performingStagesMayPool() {
            // ACCOUNT is permitted everywhere: flooring a performing exposure account by account
            // is more expensive and not wrong. Only the pooled basis is restricted, and only in
            // Stage 3 — so this is asserted across the stages rather than on one constant.
            for (Stage stage : List.of(Stage.STAGE_1, Stage.STAGE_2)) {
                for (FloorBasis basis : FloorBasis.values()) {
                    assertThat(FloorApplication.apply(
                        ACCOUNTING_ECL, FLOOR_60_PCT, basis, stage).breaches())
                        .as("%s on a %s basis", stage, basis)
                        .isEmpty();
                }
            }
            assertThat(FloorBasis.mandatoryFor(Stage.STAGE_1)).isEqualTo(FloorBasis.PORTFOLIO);
            assertThat(FloorBasis.mandatoryFor(Stage.STAGE_3)).isEqualTo(FloorBasis.ACCOUNT);
        }

        @Test
        @DisplayName("the basis breach is not the retention breach, and neither hides the other")
        void bothCanBreakAtOnce() {
            // The reason these are two ids. Under one, conjunction would keep the first breach's
            // deviation and drop the second — and the two remedies are different: PF-1 is fixed
            // by retaining the number, PF-2 by recomputing it. A close told only one of them
            // would fix one and report the exposure as clean.
            List<InvariantResult> results = List.of(
                FloorApplication.preFloorRetained(
                    ACCOUNTING_ECL, FLOOR_60_PCT, Money.inr("300000.00")),
                FloorApplication.basisPermitted(
                    FloorBasis.PORTFOLIO, Stage.STAGE_3, Money.inr("317044.392")));

            assertThat(InvariantResult.oneResultPerInvariant(results))
                .as("kept apart, each with its own deviation")
                .hasSize(2);
            assertThat(results.stream().map(InvariantResult::id))
                .containsExactly(InvariantId.PF_1, InvariantId.PF_2);
            assertThat(results).noneMatch(InvariantResult::satisfied);
        }
    }
}
