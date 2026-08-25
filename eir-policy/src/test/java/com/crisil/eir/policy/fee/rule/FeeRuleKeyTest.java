package com.crisil.eir.policy.fee.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four-part key of FR-201, and the two properties everything above it rests on: the fee code is
 * never a wildcard, and specificity is a number with a documented order.
 */
class FeeRuleKeyTest {

    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);
    private static final LocalDate JUNE = LocalDate.of(2027, 6, 30);

    @Nested
    @DisplayName("the fee code is never a wildcard")
    class FeeCodeIsConcrete {

        @Test
        @DisplayName("a '*' fee code is refused, because it would make FR-202 unenforceable")
        void wildcardFeeCodeIsRefused() {
            // Not a validation nicety. A (*, *, *) row classifies every posting in the book, so no
            // code is ever unmapped and the exception queue stays empty — not because the taxonomy
            // is complete but because nothing can miss. FR-202's guarantee would then be
            // unfalsifiable, which is worse than absent: an empty queue would read as evidence.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeRuleKey.catchAll("*", APRIL))
                .withMessageContaining("would classify every posting");
        }

        @Test
        @DisplayName("a blank fee code is refused, because a refusal must name the key")
        void blankFeeCodeIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeRuleKey.catchAll("   ", APRIL))
                .withMessageContaining("a fee rule key needs a fee code");
        }

        @Test
        @DisplayName("codes are upper-cased, so a core-banking export in mixed case still resolves")
        void codesAreNormalised() {
            // Fee masters arrive from whatever screen the code was entered on. 'PROC_FEE' resolving
            // while 'proc_fee' raises UNMAPPED_FEE_CODE would be a control that fires on data entry
            // rather than on classification, and it would fire thousands of times.
            assertThat(FeeRuleKey.catchAll("  proc_fee  ", APRIL).feeCode())
                .as("trimmed and upper-cased")
                .isEqualTo("PROC_FEE");
        }
    }

    @Nested
    @DisplayName("specificity: product worth 2, entity worth 1")
    class Specificity {

        @Test
        @DisplayName("the four shapes rank 3, 2, 1, 0")
        void ranksAreTheDocumentedTable() {
            // Derived from the documented rule and not from the code: the rank is the sum of 2 for
            // a named product and 1 for a named entity, so exact = 2+1 = 3, product-only = 2,
            // entity-only = 1, and the per-code default = 0.
            assertThat(FeeRuleKey.of("PROC_FEE", "HOME_LOAN", "BANK_IN", APRIL).specificity())
                .as("named product and named entity: 2 + 1")
                .isEqualTo(3);
            assertThat(FeeRuleKey.forProduct("PROC_FEE", "HOME_LOAN", APRIL).specificity())
                .as("named product only: 2")
                .isEqualTo(2);
            assertThat(FeeRuleKey.forEntity("PROC_FEE", "BANK_IN", APRIL).specificity())
                .as("named entity only: 1")
                .isEqualTo(1);
            assertThat(FeeRuleKey.catchAll("PROC_FEE", APRIL).specificity())
                .as("the mandatory per-code default of 04 § 2.5: 0")
                .isZero();
        }

        @Test
        @DisplayName("null, blank and '*' are one value, not three")
        void absentDimensionsCollapse() {
            // Source configuration spells an absent dimension all three ways. Treating them as
            // different values would put rules in the set that a reader cannot tell apart, and
            // FeeRuleSet's duplicate-key rejection — the thing that makes precedence total — would
            // not catch them.
            FeeRuleKey viaNull = FeeRuleKey.of("PROC_FEE", null, null, APRIL);
            FeeRuleKey viaBlank = FeeRuleKey.of("PROC_FEE", "  ", "", APRIL);
            FeeRuleKey viaStar = FeeRuleKey.of("PROC_FEE", "*", "*", APRIL);
            assertThat(viaNull).isEqualTo(viaBlank).isEqualTo(viaStar);
            assertThat(viaNull.specificity()).as("all three are the rank 0 default").isZero();
        }
    }

    @Nested
    @DisplayName("matching a lookup")
    class Matching {

        @Test
        @DisplayName("a rule wildcard matches anything; a lookup wildcard matches only a wildcard rule")
        void wildcardMatchingIsAsymmetric() {
            FeeRuleKey productRule = FeeRuleKey.forProduct("PROC_FEE", "HOME_LOAN", APRIL);
            FeeRuleKey catchAll = FeeRuleKey.catchAll("PROC_FEE", APRIL);

            assertThat(catchAll.matches(FeeRuleKey.query("PROC_FEE", "HOME_LOAN", "BANK_IN", JUNE)))
                .as("a rule wildcard matches a named product")
                .isTrue();
            assertThat(productRule.matches(FeeRuleKey.query("PROC_FEE", null, null, JUNE)))
                .as("a posting carrying no product must not claim a product-specific rule — that"
                    + " would be a guess, and the conservative reading is a refusal")
                .isFalse();
            assertThat(catchAll.matches(FeeRuleKey.query("PROC_FEE", null, null, JUNE)))
                .as("the per-code default still answers a posting with no dimensions")
                .isTrue();
        }

        @Test
        @DisplayName("a rule takes effect on its own date, inclusive, and not before")
        void effectiveDatingIsInclusive() {
            FeeRuleKey rule = FeeRuleKey.catchAll("PROC_FEE", APRIL);
            assertThat(rule.matches(FeeRuleKey.catchAll("PROC_FEE", APRIL)))
                .as("1 April resolves against a rule effective 1 April — the same inclusive"
                    + " boundary PolicyVersion.isEffectiveOn uses")
                .isTrue();
            assertThat(rule.matches(FeeRuleKey.catchAll("PROC_FEE", APRIL.minusDays(1))))
                .as("31 March 2027 does not; a pre-loaded future repricing must be inert")
                .isFalse();
        }

        @Test
        @DisplayName("a different fee code never matches, however wildcarded")
        void feeCodeMustBeEqual() {
            assertThat(FeeRuleKey.catchAll("PROC_FEE", APRIL)
                .matches(FeeRuleKey.query("LEGAL_FEE", "HOME_LOAN", "BANK_IN", JUNE)))
                .as("the code is the one component that partitions the rule set")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the refusal has to name the key")
    class Describing {

        @Test
        @DisplayName("describe() is the four-tuple, with '*' for an absent dimension")
        void describeNamesAllFourComponents() {
            // The operator reading the exception queue has to know which of the four components to
            // configure. "Unmapped fee code" alone sends them to the fee master when the gap may be
            // a product the code was never extended to. The '*' sentinel rather than null keeps the
            // text identical on every run — an entry whose text varies between identical runs is
            // not a record.
            assertThat(FeeRuleKey.query("proc_fee", "home_loan", null, JUNE).describe())
                .isEqualTo("(PROC_FEE, HOME_LOAN, *, 2027-06-30)");
        }
    }
}
