package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.api.http.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Ind AS 107 disclosure extract (FR-806), with the FR-809 register carried inside it.
 *
 * <p><b>Why the two endpoints share a class hierarchy at all.</b> FR-806 asks for "Ind AS
 * 107-shaped disclosure extracts"; Ind AS 107.21 asks for the measurement basis and the accounting
 * policies used, and Ind AS 1.122 and 1.125 for the judgements and the estimation uncertainty. A
 * Tier 3 straight-line approximation, an ACPIR 51 contractual-life fallback, a pool EIR under the
 * group presumption and an ACPIR 54 revolving approximation are exactly those judgements. So the
 * register of FR-809 is not adjacent to the disclosure — it is one of its sections, and reading
 * it from {@link ApproximationRegister.Assembled} rather than recomputing it means the two
 * endpoints cannot publish different answers to "is the approximations register complete".
 *
 * <h2>The control this class adds</h2>
 *
 * <p>{@link #signOffPermitted} is false while any section is a gap <b>or</b> the approximations
 * register is incomplete. It is a value on a 200, not a 4xx, because a refusal is a value in this
 * engine and the whole extract comes back regardless — a reader needs the sections that <em>are</em>
 * populated in order to work on the ones that are not.
 *
 * <p>The reason it is worth having as a computed flag rather than as prose: an extract with four
 * of five sections populated looks substantially complete, and a disclosure is not substantially
 * anything. The refusals are enumerated individually so that closing them is a list of tasks
 * rather than a judgement call, and every refusal names the standard paragraph it comes from.
 */
public final class IndAs107Extract {

    private final DisclosureSources sources;

    public IndAs107Extract(DisclosureSources sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** One section's disposition, mirroring {@link CategoryReturn} for the disclosure side. */
    private record SectionReturn(
        DisclosureSection section,
        CategoryReturn.Status status,
        List<DisclosureSources.DisclosureLine> lines,
        String gap) {
    }

    /** The {@code GET /api/reports/disclosure/ind-as-107} body. */
    public Json.Obj render(ApproximationRegister.Assembled register) {
        Objects.requireNonNull(register, "register");
        List<SectionReturn> sections = new ArrayList<>();
        // Over values(), for the reason ApproximationRegister loops over its own enum: a section
        // omitted from a note to the accounts is worse than one carrying a labelled hole.
        for (DisclosureSection section : DisclosureSection.values()) {
            sections.add(section.fedByTheApproximationsRegister()
                ? fromRegister(section, register)
                : fromSource(section, register.periodId()));
        }

        List<String> refusals = signOffRefusals(sections, register);
        List<Json.Obj> rendered = new ArrayList<>(sections.size());
        int gaps = 0;
        int withLines = 0;
        for (SectionReturn section : sections) {
            if (section.status() == CategoryReturn.Status.NOT_AVAILABLE) {
                gaps++;
            }
            if (section.status() == CategoryReturn.Status.REPORTED) {
                withLines++;
            }
            rendered.add(renderSection(section));
        }

        return Json.object()
            .str("report", "disclosure/ind-as-107")
            .str("requirement", "FR-806")
            .str("specSection", "06 § 7")
            .str("period", Integer.toString(register.periodId()))
            .str("asOf", register.asOf().toString())
            // The register's own completeness is part of this extract's completeness, and leaving
            // it out let the extract publish complete: true over a measurement-basis note whose
            // every line read "NOT DISCLOSABLE". The section is always REPORTED — see
            // fromRegister — so it never counted towards `gaps`, and a consumer keying off this
            // one flag read coverage across a wholly unsourced FR-809 register.
            .bool("complete", gaps == 0 && register.complete())
            .bool("signOffPermitted", refusals.isEmpty())
            // Partitioned rather than a total beside a subset of itself: an earlier draft
            // published sectionsReported: 5 next to sectionsNotAvailable: 4 on a response where
            // exactly one section carried lines, which reads as nine of five.
            .count("sectionsTotal", sections.size())
            .count("sectionsWithLines", withLines)
            .count("sectionsNotAvailable", gaps)
            .strings("signOffRefusals", refusals)
            .array("sections", rendered)
            .obj("approximationsRegister", Json.object()
                .bool("complete", register.complete())
                .count("categoriesNotAvailable", register.gaps().size())
                .count("shortcutsSought", (int) register.shortcutsSought())
                .count("shortcutsInForce", (int) register.shortcutsInForce())
                .count("undocumentedShortcuts", (int) register.undocumentedShortcuts())
                .strings("gaps", register.gaps()))
            .str("readingNote",
                "signOffPermitted is false while any section is NOT_AVAILABLE or the FR-809"
                    + " approximations register is incomplete. NOT_AVAILABLE means no source, not"
                    + " a nil balance: a loss allowance reconciliation rendered as an empty list"
                    + " of lines would read as no movement in the period, which is a substantive"
                    + " statement this extract is not in a position to make. Every refusal below"
                    + " names the standard paragraph behind it.");
    }

    /**
     * The register-fed section: one narrative line per FR-809 category, plus the completeness
     * statement and every policy parameter nobody has approved.
     *
     * <p>Always {@link CategoryReturn.Status#REPORTED} and never a gap, and the reason is worth
     * being explicit about. The disclosure of the measurement basis is not missing when the
     * register is incomplete — the disclosure is then <em>that the register is incomplete</em>,
     * which is itself the estimation-uncertainty statement Ind AS 1.125 asks for. Rendering it as
     * a gap would hide the finding inside the mechanism for reporting findings.
     */
    private static SectionReturn fromRegister(
        DisclosureSection section, ApproximationRegister.Assembled register) {
        List<DisclosureSources.DisclosureLine> lines = new ArrayList<>();
        lines.add(DisclosureSources.DisclosureLine.narrative(
            "Completeness of the approximations register (FR-809)",
            register.complete()
                ? "complete: every one of FR-809's four categories had a source. "
                    + register.shortcutsSought() + " shortcut(s) were sought and "
                    + register.shortcutsInForce() + " are in force; "
                    + register.undocumentedShortcuts() + " were sought with no evidence on file"
                : "INCOMPLETE: " + register.gaps().size() + " of "
                    + register.categories().size() + " categories had no source, so no statement"
                    + " can be made about whether an approximation is in force in those"
                    + " categories. The count of undocumented shortcuts covers only the"
                    + " categories that had a source"));
        for (CategoryReturn block : register.categories()) {
            lines.add(DisclosureSources.DisclosureLine.narrative(
                block.category().title(),
                switch (block.status()) {
                    case REPORTED -> block.rows().size() + " subject(s): "
                        + block.soughtCount() + " sought the approximation, "
                        + block.inForceCount() + " are measured on it, and "
                        + block.undocumentedCount()
                        + " sought it with no evidence on file. " + block.category()
                            .shortcut() + ". Specification: "
                        + block.category().specReference();
                    case NONE_IN_FORCE -> "none in force in the period. The source was consulted"
                        + " and answered nothing, which is a positive statement. Specification: "
                        + block.category().specReference();
                    case NOT_AVAILABLE -> "NOT DISCLOSABLE — " + block.gap();
                }));
        }
        for (PolicyParameter parameter : register.parametersRequiringBoardAttention()) {
            lines.add(DisclosureSources.DisclosureLine.narrative(
                "Estimation parameter: " + parameter.name(),
                "value " + parameter.value() + " — " + parameter.provenance().meaning()
                    + " (" + parameter.reference() + "). " + parameter.note()));
        }
        return new SectionReturn(section, CategoryReturn.Status.REPORTED, lines, null);
    }

    private SectionReturn fromSource(DisclosureSection section, int periodId) {
        ApproximationSources.Answer<List<DisclosureSources.DisclosureLine>> answer =
            sources.section(section, periodId);
        if (!answer.isAvailable()) {
            return new SectionReturn(section, CategoryReturn.Status.NOT_AVAILABLE, List.of(),
                answer.unavailable().reason() + " Would be populated by: "
                    + section.wouldBePopulatedBy() + ".");
        }
        List<DisclosureSources.DisclosureLine> lines = List.copyOf(answer.value());
        return lines.isEmpty()
            ? new SectionReturn(section, CategoryReturn.Status.NONE_IN_FORCE, lines, null)
            : new SectionReturn(section, CategoryReturn.Status.REPORTED, lines, null);
    }

    /**
     * Why the extract may not be signed off, one entry per reason.
     *
     * <p>Enumerated rather than summarised, because "the disclosure is incomplete" is not a task
     * anybody can pick up and "the loss allowance reconciliation of Ind AS 107.35H has no source"
     * is. The register's own incompleteness is a separate refusal from any missing section: a
     * disclosure whose figures are all present but whose judgements are unenumerated is not
     * signable either, and folding the two together would let one be closed by fixing the other.
     */
    private static List<String> signOffRefusals(
        List<SectionReturn> sections, ApproximationRegister.Assembled register) {
        List<String> refusals = new ArrayList<>();
        for (SectionReturn section : sections) {
            if (section.status() == CategoryReturn.Status.NOT_AVAILABLE) {
                refusals.add(section.section().name() + " (" + section.section().reference()
                    + ") has no source: " + section.gap());
            }
        }
        if (!register.complete()) {
            refusals.add("MEASUREMENT_BASIS_AND_APPROXIMATIONS (Ind AS 1.122 and 1.125): the"
                + " FR-809 approximations register is incomplete — " + register.gaps().size()
                + " of " + register.categories().size() + " categories had no source, so the"
                + " judgements and estimation uncertainty this note must disclose are not"
                + " enumerable");
        }
        if (register.undocumentedShortcuts() > 0) {
            refusals.add("MEASUREMENT_BASIS_AND_APPROXIMATIONS (Ind AS 1.122): "
                + register.undocumentedShortcuts() + " approximation(s) were sought with no"
                + " evidence on file. Sought, not merely in force: an unevidenced Tier 3"
                + " population is demoted to Tier 2 by FR-411, so a count over shortcuts still"
                + " in force would never see the population the equivalence test exists for."
                + " 03 § 10.2: \"we approximated because it was immaterial\" is a complete answer"
                + " only when the materiality assessment exists on paper with a number attached");
        }
        return List.copyOf(refusals);
    }

    private static Json.Obj renderSection(SectionReturn section) {
        List<Json.Obj> lines = new ArrayList<>(section.lines().size());
        for (DisclosureSources.DisclosureLine line : section.lines()) {
            lines.add(Json.object()
                .str("caption", line.caption())
                .figure("amount", line.amount())
                .bool("narrative", line.amount() == null)
                .str("note", line.note()));
        }
        return Json.object()
            .str("section", section.section().name())
            .str("title", section.section().title())
            .str("reference", section.section().reference())
            .bool("fedByTheApproximationsRegister",
                section.section().fedByTheApproximationsRegister())
            .str("status", section.status().name())
            .str("statusMeaning", statusMeaning(section.status()))
            .count("lineCount", section.lines().size())
            .str("gap", section.gap())
            .array("lines", lines);
    }

    /**
     * The three statuses restated in disclosure language.
     *
     * <p>{@link CategoryReturn.Status} is reused for its three-valued shape — the gap-versus-nil
     * distinction is the same idea on both endpoints — but its own {@code meaning()} text speaks
     * of shortcuts being in force, which is the approximations register's vocabulary and not a
     * disclosure note's. Rendering it verbatim here would tell a reader of the loss allowance
     * reconciliation that a shortcut is or is not in force, which is not what the section says.
     */
    private static String statusMeaning(CategoryReturn.Status status) {
        return switch (status) {
            case REPORTED -> "the source answered and this section's lines are disclosed";
            case NONE_IN_FORCE -> "the source answered and there is nothing to disclose in this"
                + " section for the period";
            case NOT_AVAILABLE -> "no source — this is a GAP and is not a nil balance; the"
                + " section is undisclosable rather than empty";
        };
    }
}
