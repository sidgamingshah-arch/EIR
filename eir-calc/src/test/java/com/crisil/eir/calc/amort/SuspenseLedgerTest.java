package com.crisil.eir.calc.amort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Interest-in-suspense as a ledger with a balance (FR-604, 03 § 7.3).
 *
 * <p>Figures are reference case 5's: contractual interest billed of 5,298.16 on the Case 1 loan
 * in the month it enters Stage 3. Movements are plain addition and subtraction and are derived by
 * hand in each test's comment.
 */
class SuspenseLedgerTest {

    private static final Money BILLED = Money.inr("5298.16");
    private static final Money NIL = Money.zero(Money.INR);

    @Nested
    @DisplayName("the balance is what the movements say it is")
    class Movements {

        @Test
        @DisplayName("a first suspended period closes at what was charged")
        void firstPeriod() {
            // 0.00 + 5,298.16 − 0 − 0 = 5,298.16
            SuspenseLedger ledger = SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL);
            assertThat(ledger.closingBalance()).isEqualTo(Money.inr("5298.16"));
            assertThat(ledger.hasBalance()).isTrue();
            assertThat(ledger.netMovement()).isEqualTo(Money.inr("5298.16"));
        }

        @Test
        @DisplayName("periods accumulate, and the next opens where the last closed")
        void accumulates() {
            // Three suspended months at the same billed amount: 3 x 5,298.16 = 15,894.48.
            SuspenseLedger third = SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL)
                .next(BILLED, NIL, NIL)
                .next(BILLED, NIL, NIL);
            assertThat(third.closingBalance()).isEqualTo(Money.inr("15894.48"));
            assertThat(third.openingBalance())
                .as("the third period opens at two months of suspension")
                .isEqualTo(Money.inr("10596.32"));
        }

        @Test
        @DisplayName("a recovery and a write-off both reduce the balance, and are not the same event")
        void recoveryAndWriteOff() {
            // 15,894.48 opening; recover 4,000 in cash and write off 1,894.48.
            // 15,894.48 + 0 − 4,000.00 − 1,894.48 = 10,000.00
            SuspenseLedger ledger = SuspenseLedger.forPeriod(
                Money.inr("15894.48"), NIL, Money.inr("4000.00"), Money.inr("1894.48"));
            assertThat(ledger.closingBalance()).isEqualTo(Money.inr("10000.00"));

            // Distinguished because only one of them reaches the cash book. A recovery is matched
            // by cash applied to interest — the fourth leg of S3-1 asserts exactly that match —
            // and a write-off is matched by nothing. Collapsing them into one "reduction" field
            // would leave that leg with nothing to reconcile against.
            assertThat(ledger.recovered()).isEqualTo(Money.inr("4000.00"));
            assertThat(ledger.writtenOff()).isEqualTo(Money.inr("1894.48"));
        }
    }

    @Nested
    @DisplayName("cure stops the charging and leaves the balance alone (FR-607)")
    class Cure {

        @Test
        @DisplayName("the balance carries forward intact and nothing is released to income")
        void balanceSurvivesCure() {
            // The requirement is an absence, so it is asserted as one. Three suspended months
            // leave 15,894.48; the cure period charges nothing and the balance is unchanged.
            // Releasing it to income here would recognise income that was correctly never
            // recognised, and it is tempting precisely because the balance is sitting there at
            // the moment the borrower recovers.
            SuspenseLedger suspended = SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL)
                .next(BILLED, NIL, NIL)
                .next(BILLED, NIL, NIL);
            SuspenseLedger cured = SuspenseLedger.onCure(suspended);

            assertThat(cured.openingBalance()).isEqualTo(Money.inr("15894.48"));
            assertThat(cured.chargedToSuspense()).isEqualTo(NIL);
            assertThat(cured.closingBalance())
                .as("intact — cure is not a suspense-ledger event")
                .isEqualTo(Money.inr("15894.48"));
            assertThat(cured.netMovement()).isEqualTo(NIL);
        }

        @Test
        @DisplayName("curing by paying arrears is two facts, and both are available")
        void cureWithRecovery() {
            // A borrower who cures by clearing arrears has cured AND recovered. onCure states the
            // no-charge case; forPeriod remains available for the period where cash also arrives.
            // 15,894.48 + 0 − 15,894.48 − 0 = 0.00
            SuspenseLedger cleared = SuspenseLedger.forPeriod(
                Money.inr("15894.48"), NIL, Money.inr("15894.48"), NIL);
            assertThat(cleared.closingBalance()).isEqualTo(NIL);
            assertThat(cleared.hasBalance()).isFalse();
        }
    }

    @Nested
    @DisplayName("a balance that cannot be carried forward throws rather than reporting")
    class Refusals {

        @Test
        @DisplayName("recovering more than was ever suspended is refused")
        void negativeClosingIsRefused() {
            // Deliberately a throw and not an InvariantResult, against the convention that data
            // conditions are returned. A negative suspense balance is not a break to report and
            // carry forward — it is a balance that cannot be carried forward at all, and every
            // period computed from it would be wrong without any later invariant attributing it
            // here. FR-905's barrier catches RuntimeException per contract, so this quarantines
            // one contract rather than failing a run.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspenseLedger.forPeriod(
                    Money.inr("5298.16"), NIL, Money.inr("6000.00"), NIL))
                .withMessageContaining("more suspended interest has left the ledger than ever"
                    + " entered it");
        }

        @Test
        @DisplayName("a movement posted as a negative magnitude is refused, not netted")
        void negativeMovementIsRefused() {
            // A recovery posted as a negative charge and a charge posted as a negative recovery
            // give the same closing balance and different ledgers, and only one of them
            // reconciles to the cash book. The direction is the field's name.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspenseLedger.forPeriod(
                    NIL, Money.inr("-4000.00"), NIL, NIL))
                .withMessageContaining("chargedToSuspense is a magnitude");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspenseLedger.forPeriod(
                    NIL, BILLED, Money.inr("-100.00"), NIL))
                .withMessageContaining("recovered is a magnitude");
        }

        @Test
        @DisplayName("closing exactly at nil is permitted")
        void nilIsFine() {
            // The boundary. Full recovery is the ordinary good outcome, and a guard written with
            // isNegative rather than a not-positive test is what makes it representable.
            assertThat(SuspenseLedger.forPeriod(BILLED, NIL, BILLED, NIL).closingBalance())
                .isEqualTo(NIL);
        }
    }
}
