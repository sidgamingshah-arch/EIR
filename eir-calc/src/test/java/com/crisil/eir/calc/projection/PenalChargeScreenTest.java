package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant PC-1: no amount excluded by Direction entered any EIR stream or the
 * gross carrying amount.
 *
 * <p>This is one of the four things that make the engine an ACPIR product rather
 * than an IFRS 9 one, and it had no test of its own. Penal charges under RBI's 2023
 * framework are <em>charges</em>, not penal interest: they are not capitalised and
 * they bear no further interest. Legacy core banking systems routinely book penal
 * amounts into the interest ledger, so the exclusion is a hard filter with a
 * positive assertion every period rather than a rule the rule set can be configured
 * out of.
 *
 * <p><strong>The two routes are not equally defensible, and that is the point.</strong>
 * On the classified-fee route the rule set has resolved every posting, so a penal
 * amount is visible and {@link FeePosting} refuses it at the ingestion boundary. On
 * the {@code LMS_AUTHORITATIVE} route a billed line arrives as a single net amount
 * with no components, so a penal charge already folded into an instalment by the
 * source system <em>cannot be detected here at all</em>. PC-1 on that route is
 * therefore a statement about provenance, not about arithmetic: it is satisfied only
 * where somebody attests to having screened the feed, and an unattested feed is a
 * finding rather than a pass.
 */
class PenalChargeScreenTest {

    private static final PenalChargeScreen.Attestation ATTESTED = new PenalChargeScreen.Attestation(
        "FINACLE-LMS", "control-EIR-07", LocalDate.of(2026, 4, 1), Money.inr("1250.00"));

    /** The Case 1 schedule as a lender would bill it, final instalment plugged. */
    private static List<Instalment> billedCase1() {
        List<Instalment> lines = new ArrayList<>();
        for (int period = 1; period <= 24; period++) {
            lines.add(Instalment.of(FIRST_DUE.plusMonths(period - 1L), period,
                Money.inr(period == 24 ? "47073.53" : "47073.47")));
        }
        return lines;
    }

    // ------------------------------------------------- the billed-schedule route

    @Test
    @DisplayName("an unattested billed feed leaves PC-1 unsatisfied, and says why rather than throwing")
    void anUnattestedFeedFailsPcOne() {
        InvariantResult result = PenalChargeScreen.overBilledSchedule("unattested feed", 24, null);

        assertThat(result.id()).isEqualTo(InvariantId.PC_1);
        assertThat(result.satisfied())
            .as("nobody screened this feed, so nothing asserts the exclusion held")
            .isFalse();
        // Unsatisfied, not thrown. A bank migrating a legacy book will meet unattested
        // feeds by the thousand, and the engine has to report every one of them rather
        // than abort the run on the first — the finding is the deliverable.
        assertThat(result.detail())
            .contains("no penal-charge attestation")
            .contains("net amount with no")
            .contains("cannot be detected here");
        assertThat(result.deviation())
            .as("the breach is measured in lines at risk, there being no amount to measure")
            .isEqualByComparingTo(BigDecimal.valueOf(24));
    }

    @Test
    @DisplayName("an attested feed satisfies PC-1 and records who screened it, when, and what came out")
    void anAttestedFeedSatisfiesPcOne() {
        InvariantResult result = PenalChargeScreen.overBilledSchedule("FINACLE-LMS", 24, ATTESTED);

        assertThat(result.id()).isEqualTo(InvariantId.PC_1);
        assertThat(result.satisfied()).isTrue();
        // The detail is the audit trail. An assertion that records only "passed" is not
        // evidence that anything was screened.
        assertThat(result.detail())
            .contains("FINACLE-LMS")
            .contains("control-EIR-07")
            .contains("2026-04-01")
            .contains("24 line(s)");
    }

    @Test
    @DisplayName("nothing removed is a legitimate attestation, and is not the same as nobody looking")
    void aNilRemovalIsStillAnAttestation() {
        // The distinction the Attestation record exists to hold. A screened feed that
        // contained no penal amount is the common case and passes; the absence of an
        // attestation is the failure. Collapsing the two would let a clean feed and an
        // unscreened one report identically.
        PenalChargeScreen.Attestation cleanFeed = new PenalChargeScreen.Attestation(
            "FINACLE-LMS", "control-EIR-07", LocalDate.of(2026, 4, 1), Money.zero(Money.INR));

        assertThat(PenalChargeScreen.overBilledSchedule("FINACLE-LMS", 24, cleanFeed).satisfied())
            .isTrue();
        assertThat(PenalChargeScreen.overBilledSchedule("FINACLE-LMS", 24, null).satisfied())
            .isFalse();
    }

    @Test
    @DisplayName("an attestation must name the feed, name the screener, and not claim a negative removal")
    void anAttestationMustBeUsable() {
        LocalDate on = LocalDate.of(2026, 4, 1);
        Money nil = Money.zero(Money.INR);

        // An attestation nobody can follow up is not an attestation. These are the fields
        // an auditor asks for by name.
        assertThatThrownBy(() -> new PenalChargeScreen.Attestation("  ", "control-EIR-07", on, nil))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must name the feed screened");
        assertThatThrownBy(() -> new PenalChargeScreen.Attestation("FINACLE-LMS", " ", on, nil))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must identify the screener");
        assertThatThrownBy(() -> new PenalChargeScreen.Attestation(
            "FINACLE-LMS", "control-EIR-07", on, Money.inr("-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new PenalChargeScreen.Attestation("FINACLE-LMS", "c", null, nil))
            .isInstanceOf(NullPointerException.class);
    }

    // ----------------------------------------------------- the wiring, end to end

    @Test
    @DisplayName("the LMS route carries PC-1 out on the projection, so an unattested feed fails the projection")
    void theProjectionCarriesTheFindingOut() {
        // The assertion that matters. The helper being right is worth little if the
        // projector does not carry its result out where the close can see it, and
        // ExternalScheduleProjector is the only caller of overBilledSchedule.
        ProjectionResult unattested =
            new ExternalScheduleProjector(billedCase1()).project(case1(), case1Fees());
        ProjectionResult attested = new ExternalScheduleProjector(billedCase1(), ATTESTED)
            .project(case1(), case1Fees());

        assertThat(pcOne(unattested).satisfied()).isFalse();
        assertThat(unattested.allInvariantsSatisfied())
            .as("an unattested feed must fail the projection, not pass it quietly")
            .isFalse();

        assertThat(pcOne(attested).satisfied()).isTrue();
        assertThat(attested.allInvariantsSatisfied()).isTrue();

        // Exactly one PC-1, carrying the evidence from both routes. pcOne() above is what
        // caught this: the LMS route used to emit a second PC-1 alongside the fee route's,
        // and on an unattested feed the two disagreed — one passing, one failing — so
        // anything reading PC-1 by name got whichever came first.
        assertThat(pcOne(unattested).detail())
            .contains("no posting classified EXCLUDED_BY_DIRECTION")
            .contains("no penal-charge attestation");
        assertThat(pcOne(attested).detail())
            .contains("no posting classified EXCLUDED_BY_DIRECTION")
            .contains("FINACLE-LMS");

        // And the flows are identical either way: the attestation is provenance, not
        // arithmetic. If attesting a feed changed a number, PC-1 would be a computation
        // rather than a control.
        assertThat(attested.contractual()).isEqualTo(unattested.contractual());
        assertThat(attested.initialCarryingAmount()).isEqualTo(unattested.initialCarryingAmount());
    }

    private static InvariantResult pcOne(ProjectionResult projection) {
        return projection.invariants().stream()
            .filter(result -> result.id() == InvariantId.PC_1)
            .reduce((first, second) -> {
                throw new AssertionError("PC-1 asserted twice on one projection");
            })
            .orElseThrow(() -> new AssertionError("PC-1 was not asserted on the projection"));
    }

    // ------------------------------------------------- the classified-fee route

    @Test
    @DisplayName("combining PC-1 assertions takes the conjunction and keeps every route's evidence")
    void combiningTakesTheConjunction() {
        InvariantResult feeRoute = PenalChargeScreen.overFeePostings(case1Fees());
        InvariantResult unattested = PenalChargeScreen.overBilledSchedule("feed", 24, null);
        InvariantResult attested = PenalChargeScreen.overBilledSchedule("FINACLE-LMS", 24, ATTESTED);

        assertThat(PenalChargeScreen.combine(List.of(feeRoute, attested)).satisfied()).isTrue();
        // One failing route fails the control, whichever order they arrive in.
        assertThat(PenalChargeScreen.combine(List.of(feeRoute, unattested)).satisfied()).isFalse();
        assertThat(PenalChargeScreen.combine(List.of(unattested, feeRoute)).satisfied()).isFalse();
        assertThat(PenalChargeScreen.combine(List.of(feeRoute, unattested)).deviation())
            .as("the failing route's magnitude survives the combination")
            .isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(PenalChargeScreen.combine(List.of(feeRoute)))
            .as("a single assertion passes through untouched rather than being reworded")
            .isEqualTo(feeRoute);

        // PC-1 must be asserted on every projection, so an empty combination is a
        // programming error rather than a silent pass — a control that can vanish is not
        // a control.
        assertThatThrownBy(() -> PenalChargeScreen.combine(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be asserted on every projection");
        assertThatThrownBy(() -> PenalChargeScreen.combine(
            List.of(feeRoute, InvariantResult.pass(InvariantId.IC_1, "not PC-1"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only PC-1 assertions combine here");
    }

    @Test
    @DisplayName("PC-1 passes positively over classified fees, naming how many were screened")
    void theFeeRoutePassesPositively() {
        InvariantResult result = PenalChargeScreen.overFeePostings(case1Fees());

        assertThat(result.id()).isEqualTo(InvariantId.PC_1);
        assertThat(result.satisfied()).isTrue();
        // Positive, not vacuous: it states the count screened. A control that emits
        // nothing when there is nothing wrong leaves no evidence that it ran.
        assertThat(result.detail())
            .contains("no posting classified EXCLUDED_BY_DIRECTION")
            .contains(case1Fees().size() + " posting(s) screened");

        assertThat(PenalChargeScreen.overFeePostings(List.of()).satisfied())
            .as("an unfee'd contract is screened too, and says so")
            .isTrue();
    }

    @Test
    @DisplayName("a penal posting cannot reach the screen at all: FeePosting refuses it at the boundary")
    void theFeeRouteRefusesAPenalPostingUpstream() {
        // Why the failing branch of overFeePostings has no test: it is unreachable. The
        // classification arrives already resolved by the rule set, and FeePosting's
        // canonical constructor rejects EXCLUDED_BY_DIRECTION outright, so no instance
        // carrying it can be built by any caller. That branch is a guard against a future
        // change relaxing this constructor, not a live path — and this test is what pins
        // the constructor down so the guard stays unreachable for the stated reason.
        assertThatThrownBy(() -> new FeePosting("PENAL_CHG", Money.inr("2500"),
            LocalDate.of(2026, 4, 1), FeeClassification.EXCLUDED_BY_DIRECTION, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot enter any EIR cash flow stream")
            .hasMessageContaining("charges, not penal interest")
            .hasMessageContaining(InvariantId.PC_1.toString());
    }
}
