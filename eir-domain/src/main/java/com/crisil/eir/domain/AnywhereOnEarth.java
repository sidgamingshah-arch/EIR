package com.crisil.eir.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Converts a zoneless {@link LocalDate} into the widest instant range that date could possibly
 * denote, so that a check comparing a date against an instant can be made conservative.
 *
 * <p>Two of this package's records mix the two kinds of time and cannot be changed to agree:
 * {@code PolicyVersion.approvedOn} and {@code ImpactPreview.portfolioAsOf()} are dates, because
 * that is how an approval and a book position are recorded, while
 * {@code ImpactPreview.generatedAt()} is an instant, because a preview's age has to be measurable
 * to better than a day. Comparing them requires a zone that neither record carries.
 *
 * <p><b>Assuming UTC would be wrong in the expensive direction.</b> This engine runs for an
 * Indian bank at UTC+05:30, so a preview generated at 03:00 IST on the 16th is 21:30 UTC on the
 * 15th, and a naive UTC comparison would place it on the wrong side of a day boundary. Getting
 * that wrong produces a <em>false</em> refusal — a policy version blocked when nothing is
 * actually wrong with its preview — and a hard gate that produces false refusals is a hard gate
 * that gets argued down to a soft one. So the checks that use this widen the date to every
 * instant it could denote anywhere, and fire only outside that range, where no clock on earth
 * can reconcile the two records.
 *
 * <p>The real-world offsets are UTC-12 ("anywhere on Earth", the last place a date is still
 * current) and UTC+14 (the first place it begins). {@link ZoneOffset#MIN} and
 * {@link ZoneOffset#MAX} reach ±18:00, which no jurisdiction uses; using them would widen the
 * range by another six hours for no gain in honesty.
 *
 * <p><b>Why this lives in {@code eir-domain}.</b> It was package-private to
 * {@code policy.preview}, and {@code policy.close.PeriodCloseGate} then needed the same widening
 * for the same reason — an operator's close timestamp against a period end date — and copied the
 * {@code UTC+14} constant with a comment saying it belonged here. Two statements of one rule is
 * this codebase's recurring defect, and a date-to-instant widening with no dependencies is
 * exactly the shape {@code eir-domain} is for. Both callers now read the same constant, so the
 * conservative range cannot drift between the gate that admits a policy version and the gate that
 * closes a period.
 */
public final class AnywhereOnEarth {

    /** UTC-12: the last real-world clock to leave a calendar date. */
    private static final ZoneOffset LATEST = ZoneOffset.ofHours(-12);

    /** UTC+14: the first real-world clock to enter one. */
    private static final ZoneOffset EARLIEST = ZoneOffset.ofHours(14);

    private AnywhereOnEarth() {
    }

    /**
     * The first instant at which {@code date} has begun somewhere — {@code date} 00:00 at UTC+14.
     * Anything before this is unambiguously earlier than {@code date}.
     */
    public static Instant earliestInstantOf(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.atStartOfDay(EARLIEST).toInstant();
    }

    /**
     * The first instant at which {@code date} has ended everywhere — the following day's
     * midnight at UTC-12. Anything at or after this is unambiguously later than {@code date}.
     */
    public static Instant firstInstantAfter(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.plusDays(1).atStartOfDay(LATEST).toInstant();
    }
}
