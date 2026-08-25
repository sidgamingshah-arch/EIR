package com.crisil.eir.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The result type's own invariants: a transition outcome is a moved version or a reason, never
 * both, never neither, and never a version in a status nobody asked for.
 *
 * <p>Asserted separately from the gate because these are the guards that make a refusal safe to
 * report. A result carrying both a version and a refusal is one a caller could read the version
 * out of while logging the refusal, which is the failure mode of every "return null on error"
 * API this type exists to avoid.
 */
class TransitionResultTest {

    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate SIGNED_ON = LocalDate.of(2027, 3, 15);

    private static PolicyVersion versionIn(PolicyVersionStatus status) {
        boolean signed = status.isApproved();
        return new PolicyVersion(
            "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
            EFFECTIVE_FROM, "policy.author", signed ? "accounting.policy.owner" : null,
            signed ? SIGNED_ON : null, status);
    }

    private static TransitionRequest asking(
        PolicyVersionStatus from, PolicyVersionStatus target) {
        return new TransitionRequest(versionIn(from), target, null, null, "");
    }

    @Nested
    @DisplayName("exactly one of a version and a refusal")
    class Pairing {

        @Test
        @DisplayName("neither is a refusal with no reason")
        void neitherIsRejected() {
            // The thing this type exists to make impossible: a refusal a reader cannot act on.
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TransitionResult(
                    asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                    null, null, "nothing said either way"))
                .withMessageContaining("never both and never neither");
        }

        @Test
        @DisplayName("both is a refusal somebody can read a version out of")
        void bothIsRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TransitionResult(
                    asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                    versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                    TransitionRefusal.SKIPS_APPROVAL, "both"))
                .withMessageContaining("never both and never neither");
        }

        @Test
        @DisplayName("an allowed result must land in the status that was asked for")
        void statusMustMatchTheTarget() {
            // Catches the one mistake that makes success actively misleading — a gate that
            // returned the version unchanged, or advanced it somewhere else, while reporting that
            // the transition happened. The caller's next act is to persist what it was handed.
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> TransitionResult.allowed(
                    asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.APPROVED),
                    versionIn(PolicyVersionStatus.PENDING_APPROVAL), "advanced somewhere else"))
                .withMessageContaining("produced a version in status PENDING_APPROVAL");
        }

        @Test
        @DisplayName("a refusal still says what was attempted")
        void refusalRetainsTheTarget() {
            TransitionResult refused = TransitionResult.refused(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE),
                TransitionRefusal.SKIPS_APPROVAL, "d");
            assertThat(refused.isRefused()).isTrue();
            assertThat(refused.isAllowed()).isFalse();
            assertThat(refused.target())
                .as("the attempted status survives the refusal")
                .isEqualTo(PolicyVersionStatus.EFFECTIVE);
            assertThat(refused.before().status()).isEqualTo(PolicyVersionStatus.DRAFT);
            assertThat(refused.after()).isNull();
        }

        @Test
        @DisplayName("the mandatory fields are mandatory")
        void nullsThrow() {
            assertThatNullPointerException().isThrownBy(() -> TransitionResult.refused(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE), null, "d"));
            assertThatNullPointerException().isThrownBy(() -> TransitionResult.allowed(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                null, "d"));
            assertThatNullPointerException().isThrownBy(() -> new TransitionResult(
                null, versionIn(PolicyVersionStatus.DRAFT), null, "d"));
        }
    }

    @Nested
    @DisplayName("reading the outcome")
    class Reading {

        @Test
        @DisplayName("versionOrThrow carries the refusal into the message")
        void versionOrThrowNamesTheRefusal() {
            // The batch path needs refusals as data; a caller that has already checked
            // isAllowed() should not have to defend against a null it has proved cannot be there.
            TransitionResult refused = TransitionResult.refused(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE),
                TransitionRefusal.SKIPS_APPROVAL, "DRAFT -> EFFECTIVE on FEE-2027.1");
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(refused::versionOrThrow)
                .withMessageContaining("SKIPS_APPROVAL")
                .withMessageContaining("FEE-2027.1");

            PolicyVersion moved = versionIn(PolicyVersionStatus.PENDING_APPROVAL);
            assertThat(TransitionResult
                .allowed(asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                    moved, "d")
                .versionOrThrow())
                .isSameAs(moved);
        }

        @Test
        @DisplayName("the successor id survives as a value, not only inside a sentence")
        void successorSurvivesTheTransition() {
            // PolicyVersion has no superseded_by field, so `after` cannot carry the pointer and
            // whoever persists the transition is the only party that can store it. A successor
            // that lived only in the detail string would be lost the moment the row was written —
            // leaving exactly the state MISSING_SUCCESSOR refuses.
            TransitionRequest request = new TransitionRequest(
                versionIn(PolicyVersionStatus.EFFECTIVE), PolicyVersionStatus.SUPERSEDED,
                null, "FEE-2027.2", "");
            TransitionResult result = TransitionResult.allowed(
                request, versionIn(PolicyVersionStatus.SUPERSEDED), "superseded");
            assertThat(result.successorVersionId()).isEqualTo("FEE-2027.2");

            assertThat(TransitionResult
                .allowed(asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                    versionIn(PolicyVersionStatus.PENDING_APPROVAL), "submitted")
                .successorVersionId())
                .as("a submission replaces nothing")
                .isNull();
        }

        @Test
        @DisplayName("refusalsIn keeps only refusals, in presentation order")
        void refusalsInFilters() {
            // Presentation order rather than grouped by reason: the operator's next act is to fix
            // the inputs and re-present them, which is done in the order the inputs are held.
            TransitionResult allowed = TransitionResult.allowed(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                versionIn(PolicyVersionStatus.PENDING_APPROVAL), "submitted");
            TransitionResult first = TransitionResult.refused(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE),
                TransitionRefusal.SKIPS_APPROVAL, "first");
            TransitionResult second = TransitionResult.refused(
                asking(PolicyVersionStatus.APPROVED, PolicyVersionStatus.SUPERSEDED),
                TransitionRefusal.MISSING_SUCCESSOR, "second");

            assertThat(TransitionResult.refusalsIn(List.of(first, allowed, second)))
                .containsExactly(first, second);
            assertThat(TransitionResult.refusalsIn(List.of(allowed)))
                .as("a clean batch reports nothing")
                .isEmpty();
        }

        @Test
        @DisplayName("the audit line names the outcome and the particulars, once each")
        void describeIsSelfContained() {
            // Hand-written expectation. The detail the gate builds already names the version and
            // both states, so the audit line prefixes the outcome and does not repeat them —
            // a line that said "FEE-2027.1 DRAFT -> EFFECTIVE" twice would be harder to read,
            // not more complete.
            TransitionResult refused = TransitionResult.refused(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE),
                TransitionRefusal.SKIPS_APPROVAL,
                "FEE_RULE_SET version FEE-2027.1 DRAFT -> EFFECTIVE refused: no signature");
            assertThat(refused.describe())
                .isEqualTo("REFUSED SKIPS_APPROVAL — FEE_RULE_SET version FEE-2027.1"
                    + " DRAFT -> EFFECTIVE refused: no signature");

            assertThat(TransitionResult.allowed(
                asking(PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL),
                versionIn(PolicyVersionStatus.PENDING_APPROVAL), "submitted").describe())
                .isEqualTo("allowed — submitted");
        }

        @Test
        @DisplayName("every refusal reason explains itself in general terms")
        void everyRefusalCarriesAnExplanation() {
            // Across the enum rather than on one constant, so that adding a reason forces a
            // sentence to be written for it rather than inheriting an empty one. The detail names
            // the particulars; this is the rule.
            for (TransitionRefusal refusal : TransitionRefusal.values()) {
                assertThat(refusal.explanation()).as("%s", refusal).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("the request that was asked for")
    class Request {

        @Test
        @DisplayName("a blank successor is absent, not a successor named empty")
        void blankSuccessorIsAbsent() {
            // Normalised once, here, so the gate's checks are stated in present-or-absent terms.
            // A CSV column or an untouched form field arrives as "" and means "nothing given".
            TransitionRequest request = new TransitionRequest(
                versionIn(PolicyVersionStatus.EFFECTIVE), PolicyVersionStatus.SUPERSEDED,
                null, "   ", null);
            assertThat(request.namesSuccessor()).isFalse();
            assertThat(request.successorVersionId()).isNull();
            assertThat(request.note()).as("an absent note is empty, not null").isEmpty();

            TransitionRequest named = new TransitionRequest(
                versionIn(PolicyVersionStatus.EFFECTIVE), PolicyVersionStatus.SUPERSEDED,
                null, "  FEE-2027.2  ", " replaced ");
            assertThat(named.successorVersionId()).isEqualTo("FEE-2027.2");
            assertThat(named.note()).isEqualTo("replaced");
            assertThat(named.namesSuccessor()).isTrue();
        }

        @Test
        @DisplayName("from() is where the version is, not where it is going")
        void fromIsTheOriginStatus() {
            TransitionRequest request = new TransitionRequest(
                versionIn(PolicyVersionStatus.APPROVED), PolicyVersionStatus.EFFECTIVE,
                null, null, "quarter-end activation");
            assertThat(request.from()).isEqualTo(PolicyVersionStatus.APPROVED);
            assertThat(request.hasApproval()).isFalse();
            assertThat(request.describe())
                .isEqualTo("FEE_RULE_SET version FEE-2027.1 APPROVED -> EFFECTIVE"
                    + " (quarter-end activation)");
        }

        @Test
        @DisplayName("a request needs a version and a target")
        void nullsThrow() {
            assertThatNullPointerException().isThrownBy(() -> new TransitionRequest(
                null, PolicyVersionStatus.APPROVED, null, null, ""));
            assertThatNullPointerException().isThrownBy(() -> new TransitionRequest(
                versionIn(PolicyVersionStatus.DRAFT), null, null, null, ""));
        }
    }
}
