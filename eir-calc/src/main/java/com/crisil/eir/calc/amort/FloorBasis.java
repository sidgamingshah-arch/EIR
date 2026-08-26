package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.Stage;

/**
 * The level at which an ACPIR 90 prudential floor is applied (03 § 7.5).
 *
 * <p>Two constants and one rule between them, and the rule is the reason the type exists rather
 * than a boolean: ACPIR 90 applies the floor per product category on a <em>portfolio</em> basis
 * for Stages 1 and 2, and <b>mandatorily at account level for Stage 3</b>. A boolean called
 * {@code accountLevel} would carry the same information and none of the requirement.
 */
public enum FloorBasis {

    /**
     * Per product category, across the pool. Permitted for Stages 1 and 2.
     *
     * <p>What makes it wrong for Stage 3: a pooled floor averages the shortfall on accounts that
     * have one against accounts that do not, which understates the floor on exactly the exposures
     * where it binds hardest.
     */
    PORTFOLIO,

    /** Per exposure. Mandatory for Stage 3, and permitted anywhere. */
    ACCOUNT;

    /**
     * Whether this basis is permitted for {@code stage}.
     *
     * <p>{@link #ACCOUNT} is permitted everywhere — flooring a performing exposure account by
     * account is more expensive and not wrong. Only the pooled basis is restricted, and only in
     * Stage 3.
     */
    public boolean isPermittedFor(Stage stage) {
        return this == ACCOUNT || !stage.suppressesIncomeRecognition();
    }

    /** The basis ACPIR 90 requires for {@code stage} where only one is permitted. */
    public static FloorBasis mandatoryFor(Stage stage) {
        return stage.suppressesIncomeRecognition() ? ACCOUNT : PORTFOLIO;
    }
}
