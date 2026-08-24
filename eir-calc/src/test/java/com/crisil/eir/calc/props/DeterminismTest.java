package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.DiscountInstrumentProjector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Invariant DT-1: a re-run reproduces published figures bit-identically.
 *
 * <p>This is the specification's exit gate and it is tested three ways, because
 * each catches something the others cannot.
 *
 * <p><b>Running twice</b> catches an obviously non-deterministic engine — an
 * iteration over a hash set, an accumulator that depends on allocation order. It is
 * necessary and it is weak: everything non-deterministic about a wall clock looks
 * deterministic inside one second.
 *
 * <p><b>Running twice under a moved clock</b> is the one that actually proves the
 * property. A path that silently reads "now" produces identical output all
 * afternoon and diverges eighteen months later, on the replay, at exactly the
 * moment an auditor is watching. The default time zone is the strongest lever on
 * "now" available to a test without a clock injection point: between the two runs
 * this test moves the JVM from UTC to Kiritimati (UTC+14) and then to Niue
 * (UTC-11), twenty-five hours apart, so {@code LocalDate.now()} returns two
 * different calendar dates across the pair. The default locale moves with it, to a
 * Thai-digit and then an Arabic-digit locale, because a figure formatted through
 * {@code String.format} or {@code NumberFormat} without an explicit locale renders
 * "1,234.56" as a different string in each — and a published figure that changes
 * shape with the operating-system locale is a reconciliation break with no
 * arithmetic behind it.
 *
 * <p><b>Reading the source tree</b> catches the path that no fixture exercises. A
 * grep-style assertion over the main sources is a blunt instrument and it is the
 * right one here: the ban is absolute (1.1, ADR-0003), so any occurrence is a
 * defect and no occurrence needs a judgement call. It also holds for code that
 * exists but no test has reached yet, which is precisely where a
 * {@code LocalDate.now()} defaulting a missing date tends to be written.
 */
class DeterminismTest {

    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);

    /** Identifiers that read a wall clock, or carry the zone that would let one in. */
    private static final List<Pattern> CLOCK_PATTERNS = List.of(
        Pattern.compile("\\.now\\s*\\("),
        Pattern.compile("System\\s*\\.\\s*currentTimeMillis"),
        Pattern.compile("System\\s*\\.\\s*nanoTime"),
        Pattern.compile("new\\s+Date\\s*\\("),
        Pattern.compile("\\bInstant\\b"),
        Pattern.compile("\\bClock\\b"),
        Pattern.compile("\\bZoneId\\b"),
        Pattern.compile("\\bZoneOffset\\b"),
        Pattern.compile("\\bZonedDateTime\\b"),
        Pattern.compile("\\bOffsetDateTime\\b"),
        Pattern.compile("\\bLocalDateTime\\b"),
        Pattern.compile("\\bLocalTime\\b"),
        Pattern.compile("\\bTimeZone\\b"),
        Pattern.compile("Calendar\\s*\\.\\s*getInstance"));

    /** Identifiers that introduce a value the next run will not reproduce. */
    private static final List<Pattern> RANDOMNESS_PATTERNS = List.of(
        Pattern.compile("\\bRandom\\b"),
        Pattern.compile("Math\\s*\\.\\s*random"),
        Pattern.compile("ThreadLocalRandom"),
        Pattern.compile("SecureRandom"),
        Pattern.compile("randomUUID"),
        Pattern.compile("Collections\\s*\\.\\s*shuffle"));

    private final Locale originalLocale = Locale.getDefault();
    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void restoreTheAmbientEnvironment() {
        Locale.setDefault(originalLocale);
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void theSameComputationRunTwiceIsByteIdentical() {
        String first = canonicalRun();
        String second = canonicalRun();

        assertThat(first)
            .as("the canonical form must be substantial enough for the comparison to mean something")
            .hasSizeGreaterThan(4000)
            .contains("eir=")
            .contains("invariant=");
        assertThat(digest(second))
            .as("DT-1 on a repeat run: %s against %s", digest(first), digest(second))
            .isEqualTo(digest(first));
        assertThat(second).isEqualTo(first);
    }

    /**
     * The same computation with the JVM's clock and locale moved between runs.
     *
     * <p>Three runs, twenty-five hours of time-zone travel and two numbering systems
     * apart, must produce one string. A difference here is not a rounding
     * disagreement — it is a path that read something the calculation is not allowed
     * to know.
     */
    @Test
    void theSameComputationUnderAMovedClockAndForeignLocaleIsByteIdentical() {
        String underUtc = runUnder("UTC", Locale.ROOT);
        String underKiritimati = runUnder("Pacific/Kiritimati", Locale.forLanguageTag("th-TH-u-nu-thai"));
        String underNiue = runUnder("Pacific/Niue", Locale.forLanguageTag("ar-EG-u-nu-arab"));

        assertThat(underKiritimati)
            .as("UTC+14 with Thai digits against UTC: %s vs %s",
                digest(underKiritimati), digest(underUtc))
            .isEqualTo(underUtc);
        assertThat(underNiue)
            .as("UTC-11 with Arabic-Indic digits against UTC: %s vs %s",
                digest(underNiue), digest(underUtc))
            .isEqualTo(underUtc);
    }

    /**
     * No main source in {@code eir-domain} or {@code eir-calc} reads a wall clock.
     *
     * <p>Comments and string literals are blanked before matching, so a paragraph
     * explaining why {@code LocalDate.now()} is banned does not itself trip the ban.
     * Line numbers survive the blanking, so a hit reports where it is.
     */
    @Test
    void noSourceInTheCalculationPathReadsAClock() {
        assertThat(scanMainSources(CLOCK_PATTERNS))
            .as("time is always an input (1.1). A calculation that reads 'now' cannot be replayed,"
                + " which is invariant DT-1 and the whole basis of the event-sourced recompute"
                + " (ADR-0003)")
            .isEmpty();
    }

    /** No main source draws a random value: the same inputs must give the same figures. */
    @Test
    void noSourceInTheCalculationPathDrawsARandomValue() {
        assertThat(scanMainSources(RANDOMNESS_PATTERNS))
            .as("randomness in the calculation path makes a published figure unreproducible,"
                + " and an unreproducible figure cannot be audited")
            .isEmpty();
    }

    /** The scan is only worth having if it is actually reading files. */
    @Test
    void theSourceScanReachesBothModules() {
        List<Path> sources = mainSources();

        assertThat(sources).hasSizeGreaterThan(30);
        assertThat(sources.stream().anyMatch(path -> path.toString().contains("eir-domain")))
            .as("eir-domain sources were found")
            .isTrue();
        assertThat(sources.stream().anyMatch(path -> path.toString().contains("eir-calc")))
            .as("eir-calc sources were found")
            .isTrue();
        // And the scanner sees what is there: the banned pattern in this test's own
        // source is found when the same scan is pointed at it.
        assertThat(matches(readBlanked(testSource()), CLOCK_PATTERNS, testSource()))
            .as("the scanner detects the patterns it is looking for")
            .isNotEmpty();
    }

    // ------------------------------------------------------------- the pipeline

    private static String runUnder(String zone, Locale locale) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        Locale.setDefault(locale);
        return canonicalRun();
    }

    /**
     * Four instruments chosen to cover both time conventions, both fee signs, a
     * capitalising moratorium and a zero-coupon accretion, serialised into one
     * string.
     *
     * <p>Explicit rather than generated: a determinism test compares two runs of the
     * <em>same</em> computation, so the inputs must not come from a random source at
     * all. The dates are constants for the same reason the reference-case fixture's
     * are.
     */
    private static String canonicalRun() {
        StringBuilder text = new StringBuilder(16384);
        for (Generators.Contract contract : representativeContracts()) {
            text.append(Generators.canonicalise(Generators.run(contract)));
            text.append("----\n");
        }
        return text.toString();
    }

    private static List<Generators.Contract> representativeContracts() {
        List<Generators.Contract> contracts = new ArrayList<>();
        Rate onePercentMonthly = Rate.monthly(new BigDecimal("0.01"));
        ContractTerms emi = ContractTerms.of(Money.inr("1000000"), onePercentMonthly, 24, 12,
            DISBURSEMENT, DISBURSEMENT.plusMonths(1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI, RateType.FIXED);
        contracts.add(contract(emi, Money.inr("15000"), Money.inr("10000")));
        contracts.add(contract(emi.withMoratorium(6, true), Money.inr("15000"), Money.inr("10000")));
        contracts.add(contract(ContractTerms.of(Money.inr("50000000"),
            Rate.periodic(new BigDecimal("0.08"), 1), 15, 1, DISBURSEMENT,
            DISBURSEMENT.plusMonths(12), DayCountConvention.ACT_365F,
            ScheduleShape.DISCOUNT_INSTRUMENT, RateType.FIXED), Money.zero(Money.INR),
            Money.inr("125000")));
        contracts.add(contract(ContractTerms.of(Money.inr("7500000"),
            Rate.periodic(new BigDecimal("0.0275"), 4), 20, 4, LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 5, 15), DayCountConvention.ACT_ACT_ISDA,
            ScheduleShape.BALLOON, RateType.FLOATING).withBalloon(Money.inr("2000000")),
            Money.inr("90000"), Money.zero(Money.INR)));
        return contracts;
    }

    private static Generators.Contract contract(ContractTerms terms, Money received, Money paid) {
        List<FeePosting> fees = new ArrayList<>();
        if (received.isPositive()) {
            fees.add(FeePosting.received("PROCESSING_FEE", received, terms.disbursementDate(),
                FeeClassification.INTEGRAL));
        }
        if (paid.isPositive()) {
            fees.add(FeePosting.paid("DSA_COMMISSION", paid, terms.disbursementDate(),
                FeeClassification.INTEGRAL, "SELLING"));
        }
        return new Generators.Contract(terms, fees, received.minus(paid),
            terms.shape() == ScheduleShape.DISCOUNT_INSTRUMENT
                ? new DiscountInstrumentProjector().issuePrice(terms).atPresentationScale()
                : terms.principal().atPresentationScale());
    }

    // -------------------------------------------------------------- the scanner

    private static List<String> scanMainSources(List<Pattern> patterns) {
        List<String> hits = new ArrayList<>();
        for (Path source : mainSources()) {
            hits.addAll(matches(readBlanked(source), patterns, source));
        }
        return hits;
    }

    private static List<String> matches(List<String> lines, List<Pattern> patterns, Path source) {
        List<String> hits = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    hits.add(source + ":" + (index + 1) + " matched /" + pattern.pattern() + "/ in: "
                        + line.strip());
                }
            }
        }
        return hits;
    }

    private static List<Path> mainSources() {
        Path root = repositoryRoot();
        List<Path> sources = new ArrayList<>();
        for (String module : List.of("eir-domain", "eir-calc")) {
            Path tree = root.resolve(module).resolve("src/main/java");
            try (Stream<Path> walk = Files.walk(tree)) {
                walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + tree, e);
            }
        }
        return sources;
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * <p>Surefire runs with the module directory as the working directory, but a
     * scan that silently found nothing would be a test that always passes, so the
     * search fails loudly instead of defaulting.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("eir-domain/src/main/java"))
                && Files.isDirectory(candidate.resolve("eir-calc/src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("cannot locate the repository root from "
            + Path.of("").toAbsolutePath() + "; the source scan would silently pass");
    }

    private static Path testSource() {
        return repositoryRoot().resolve(
            "eir-calc/src/test/java/com/crisil/eir/calc/props/DeterminismTest.java");
    }

    /**
     * The file's lines with comments and string literals blanked to spaces.
     *
     * <p>Positions are preserved rather than removed so that a reported line number
     * is the line number in the file. Blanking rather than skipping also means a
     * banned identifier cannot hide inside a string concatenation.
     */
    private static List<String> readBlanked(Path source) {
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
        StringBuilder blanked = new StringBuilder(text.length());
        int state = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (current == '/' && next == '/') {
                        state = 1;
                        blanked.append("  ");
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = 2;
                        blanked.append("  ");
                        index++;
                    } else if (current == '"') {
                        state = 3;
                        blanked.append(' ');
                    } else if (current == '\'') {
                        state = 4;
                        blanked.append(' ');
                    } else {
                        blanked.append(current);
                    }
                }
                case 1 -> {
                    if (current == '\n') {
                        state = 0;
                        blanked.append(current);
                    } else {
                        blanked.append(' ');
                    }
                }
                case 2 -> {
                    if (current == '*' && next == '/') {
                        state = 0;
                        blanked.append("  ");
                        index++;
                    } else {
                        blanked.append(current == '\n' ? current : ' ');
                    }
                }
                case 3 -> {
                    if (current == '\\') {
                        blanked.append("  ");
                        index++;
                    } else if (current == '"') {
                        state = 0;
                        blanked.append(' ');
                    } else {
                        blanked.append(current == '\n' ? current : ' ');
                    }
                }
                default -> {
                    if (current == '\\') {
                        blanked.append("  ");
                        index++;
                    } else if (current == '\'') {
                        state = 0;
                        blanked.append(' ');
                    } else {
                        blanked.append(' ');
                    }
                }
            }
        }
        return List.of(blanked.toString().split("\n", -1));
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not optional in a JRE", e);
        }
    }
}
