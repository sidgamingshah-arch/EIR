package com.crisil.eir.application.onboarding;

/**
 * The three measurement categories of FR-103, and the only three the schema admits.
 *
 * <p><b>Aligned to the database, deliberately and literally.</b>
 * {@code eir-persistence/src/main/resources/db/migration/V1__core_entities.sql} declares
 *
 * <pre>
 * CONSTRAINT contract_measurement_category_ck
 *     CHECK (measurement_category IN ('AMORTISED_COST', 'FVOCI', 'FVTPL'))
 * </pre>
 *
 * <p>so these constant names are the stored values, character for character. That is not
 * cosmetic: the ingestion boundary reads the column into this enum and the exclusion filter
 * writes it back, and a Java-side spelling that needed a translation table would make the
 * mapping a place where a fourth category could be invented. There are three, the schema says
 * three, and {@code valueOf} is the whole reader.
 *
 * <p><b>Why an enum in this module rather than in {@code eir-domain}.</b> Nothing in
 * {@code eir-domain} or {@code eir-calc} may read a measurement category — the gate's whole
 * purpose is that those modules are never commissioned for an instrument that failed it, and
 * {@link com.crisil.eir.calc.projection.ContractTerms}'s javadoc says so outright: "Everything the
 * projectors never read — staging, allowance, hedge designation, SPPI outcome — stays out, so that
 * a projector cannot come to depend on it." Putting the category where the arithmetic can see it
 * would invite exactly the dependency the ordering exists to prevent.
 */
public enum MeasurementCategory {

    /** The EIR regime proper: interest recognised at the effective interest rate. */
    AMORTISED_COST,

    /**
     * Fair value through other comprehensive income.
     *
     * <p>Carries an EIR: interest income and the fee amortisation run through profit or loss on
     * the effective-interest basis exactly as at amortised cost, and only the remeasurement to
     * fair value goes to OCI. So FVOCI is on the same side of this gate as amortised cost, which
     * is why 05 § 3.1's sequence diagram brackets them together as one branch
     * ("Amortised cost or FVOCI") rather than giving FVOCI a path of its own.
     */
    FVOCI,

    /**
     * Fair value through profit or loss — <b>no EIR arises at all</b> (FR-103).
     *
     * <p>The whole instrument is remeasured to fair value each period and the movement goes to
     * profit or loss. There is no carrying amount accreting at a rate, so there is no rate. 05
     * § 3.1: "On the asset side an SPPI failure is a cliff, not a gradient: the whole instrument
     * goes to FVTPL and no EIR arises."
     *
     * <p>FR-103 words the consequence as an obligation rather than a permission — an instrument
     * at FVTPL "must be excluded from EIR processing entirely" — and that is stronger than
     * "produces no rate". A contract that reaches the solver and is then discarded has already
     * consumed the projection, and worse, has produced a number that exists in memory next to
     * numbers that are real.
     */
    FVTPL;

    /**
     * Whether an effective interest rate arises for an instrument in this category.
     *
     * <p>The single predicate the pipeline branches on, so that "does this instrument have an
     * EIR" is answered in one place. Two categories yes, one no.
     */
    public boolean carriesEir() {
        return this != FVTPL;
    }
}
