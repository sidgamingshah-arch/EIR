package com.crisil.eir.policy.close;

import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The restatement artefacts on file — what has been corrected, in which closed period, and where
 * the movement was recognised.
 *
 * <p>Immutable and in-memory, in the same spirit as {@code ImpactPreviewRegister}: 04 § 2.13's
 * persistence of the artefact is {@code eir-persistence}'s concern, and what this module needs is a
 * concrete meaning for "a restatement is on file" so that the close package can be exercised end to
 * end.
 *
 * <p>It is <b>not</b> an excuse register. {@link ClosedPeriodImmutability} consults it only to make
 * a CL-1 breach's detail more useful — a mutated figure that also has a restatement on file is the
 * worst case rather than the excused one, because the movement has then been recognised twice, once
 * in the closed period and once in the open one. Nothing here can turn a CL-1 breach into a pass.
 */
public final class RestatementRegister {

    private final List<RestatementArtefact> artefacts;

    private RestatementRegister(List<RestatementArtefact> artefacts) {
        this.artefacts = List.copyOf(artefacts);
    }

    /** A register holding nothing — the ordinary state of a book nobody has had to restate. */
    public static RestatementRegister empty() {
        return new RestatementRegister(List.of());
    }

    /** A register over the given artefacts, in the order supplied. */
    public static RestatementRegister of(List<RestatementArtefact> artefacts) {
        Objects.requireNonNull(artefacts, "artefacts");
        return new RestatementRegister(artefacts);
    }

    /** A register with one more artefact; this one is unchanged. */
    public RestatementRegister with(RestatementArtefact artefact) {
        Objects.requireNonNull(artefact, "artefact");
        List<RestatementArtefact> extended = new ArrayList<>(artefacts);
        extended.add(artefact);
        return new RestatementRegister(extended);
    }

    /** Every artefact, in the order supplied. */
    public List<RestatementArtefact> artefacts() {
        return artefacts;
    }

    /** The artefacts correcting one closed period. */
    public List<RestatementArtefact> correcting(int periodId) {
        return artefacts.stream()
            .filter(artefact -> artefact.correctedPeriodId() == periodId)
            .toList();
    }

    /** The artefacts a later period recognises the movement of. */
    public List<RestatementArtefact> recognisedIn(int periodId) {
        return artefacts.stream()
            .filter(artefact -> artefact.recognisedInPeriodId() == periodId)
            .toList();
    }

    /** Whether a restatement of this figure of this closed period is on file. */
    public boolean holdsRestatementOf(int periodId, String figureKey) {
        Objects.requireNonNull(figureKey, "figureKey");
        return artefacts.stream().anyMatch(artefact -> artefact.restates(periodId, figureKey));
    }

    /** The figures of one closed period that have been restated, in first-appearance order. */
    public Set<String> restatedFigures(int periodId) {
        Set<String> keys = new LinkedHashSet<>();
        for (RestatementArtefact artefact : artefacts) {
            if (artefact.correctedPeriodId() == periodId) {
                keys.add(artefact.figureKey());
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    /**
     * The net movement the restatements of one period carry.
     *
     * <p>The disclosure figure: what the corrected period's total would move by. Signed, and
     * therefore not the figure to use for a materiality read — see {@link #absoluteRestated}.
     */
    public Money netRestated(int periodId, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        Money total = Money.zero(currency);
        for (RestatementArtefact artefact : correcting(periodId)) {
            total = total.plus(artefact.delta());
        }
        return total.atPresentationScale();
    }

    /**
     * The gross movement the restatements of one period carry.
     *
     * <p>Absolute values summed, and it exists because the signed total is the wrong figure for
     * deciding whether a restatement is material: a 4,00,00,000 understatement of interest income
     * and a 4,00,00,000 overstatement of fee income net to zero, and a register reporting zero
     * would say a period whose two largest lines were both wrong needed no disclosure. The same
     * reasoning governs every deviation in this engine — two breaks in opposite directions must
     * not net to a pass.
     */
    public Money absoluteRestated(int periodId, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        Money total = Money.zero(currency);
        for (RestatementArtefact artefact : correcting(periodId)) {
            total = total.plus(artefact.delta().abs());
        }
        return total.atPresentationScale();
    }

    /** How many artefacts are on file. */
    public int size() {
        return artefacts.size();
    }

    /** One audit sentence per period touched. */
    public String describe() {
        if (artefacts.isEmpty()) {
            return "no restatements on file";
        }
        StringBuilder rendered = new StringBuilder(artefacts.size() + " restatement(s) on file");
        for (RestatementArtefact artefact : artefacts) {
            rendered.append(System.lineSeparator()).append("  - ").append(artefact.describe());
        }
        return rendered.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
