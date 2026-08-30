package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010: the framework ban fails closed, and every exemption names its authority.
 *
 * <p><b>Why a test reads the build files.</b> The ban is a {@code maven-enforcer} execution in the
 * root pom's {@code <build><plugins>}, so every module inherits it — and a module opts out by
 * redeclaring the execution with {@code <phase>none</phase>}, which is <em>silent</em>. Nothing in
 * a build log says "this module is no longer checked". Maven's inheritance rules cannot express
 * "inheritable, except that these two modules may never opt out", so the part of ADR-0010 that
 * matters is not enforceable by the plugin that does the enforcing.
 *
 * <p>Hence this. It is the same move the persistence module makes for its DDL: a rule that lives in
 * a file nothing parses is a comment, and a control that fails open reports a green.
 *
 * <p><b>What it asserts.</b> Three things, and the third is the one worth having:
 *
 * <ol>
 *   <li>the root still declares the ban, transitively, with {@code <fail>true</fail>};</li>
 *   <li>{@code eir-domain} and {@code eir-calc} carry no override — 05 § 2.1 commits to those two
 *       and no future pom may exempt them;</li>
 *   <li>every module that <em>does</em> override it carries a {@code FRAMEWORK EXEMPTION:} line
 *       naming the authority that permits it.</li>
 * </ol>
 */
class FrameworkBanTest {

    private static final String EXECUTION_ID = "ban-frameworks-in-calculation-path";
    private static final String EXEMPTION_MARKER = "FRAMEWORK EXEMPTION:";

    /** The two modules 05 § 2.1 commits to. Neither may ever be exempted. */
    private static final List<String> AUDIT_SURFACE = List.of("eir-domain", "eir-calc");

    private static Path root;
    private static String rootPom;
    private static Map<String, String> modulePoms;

    @BeforeAll
    static void readPoms() throws IOException {
        root = repositoryRoot();
        rootPom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);

        modulePoms = new LinkedHashMap<>();
        Matcher declared = Pattern.compile("<module>([^<]+)</module>").matcher(rootPom);
        while (declared.find()) {
            String module = declared.group(1).strip();
            Path pom = root.resolve(module).resolve("pom.xml");
            assertThat(Files.exists(pom))
                    .as("module %s is declared in the root pom and has a pom.xml", module)
                    .isTrue();
            modulePoms.put(module, Files.readString(pom, StandardCharsets.UTF_8));
        }
        assertThat(modulePoms)
                .as("the root pom declares modules; a reactor of one means this test is looking"
                        + " at the wrong file")
                .hasSizeGreaterThan(1);
    }

    /**
     * Walks up from the module's own directory until it finds the aggregator.
     *
     * <p>Surefire sets the working directory to the module, and {@code ../pom.xml} would be the
     * right answer for the layout as it stands — which is exactly why it is not used. The search
     * is for a pom that declares {@code <modules>}, so the test survives a nested module without
     * silently reading a parent that is not the aggregator.
     */
    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.exists(pom)
                    && Files.readString(pom, StandardCharsets.UTF_8).contains("<modules>")) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("no aggregator pom found above " + Path.of("").toAbsolutePath());
    }

    /** Whether {@code pom} redeclares the ban execution — the silent opt-out ADR-0010 governs. */
    private static boolean overridesTheBan(String pom) {
        return pom.contains("<id>" + EXECUTION_ID + "</id>");
    }

    @Nested
    @DisplayName("the ban itself")
    class TheBan {

        @Test
        @DisplayName("the root declares it, transitively, and fails the build")
        void rootDeclaresIt() {
            assertThat(rootPom)
                    .as("the execution the whole mechanism hangs off")
                    .contains("<id>" + EXECUTION_ID + "</id>");
            assertThat(rootPom)
                    .as("transitive, or a framework arrives through a dependency of a dependency")
                    .contains("<searchTransitive>true</searchTransitive>");
            assertThat(rootPom)
                    .as("<fail>false</fail> would turn the ban into a warning nobody reads")
                    .contains("<fail>true</fail>");
        }

        @Test
        @DisplayName("it bans the five coordinates 05 s2.1 is about")
        void bansTheFrameworks() {
            for (String banned : List.of(
                    "org.springframework*:*",
                    "org.springframework.boot:*",
                    "jakarta.persistence:*",
                    "com.fasterxml.jackson.core:*",
                    "org.hibernate*:*")) {
                assertThat(rootPom)
                        .as("%s is banned in the calculation path", banned)
                        .contains("<exclude>" + banned + "</exclude>");
            }
        }

        @Test
        @DisplayName("it is inherited, not held in pluginManagement")
        void inheritedNotManaged() {
            // The distinction is the whole of ADR-0010. In pluginManagement the ban applies to
            // nobody until a module asks for it, which makes adding eir-batch a silent unbanning;
            // in build/plugins it applies to everybody until a module says otherwise in writing.
            int management = rootPom.indexOf("<pluginManagement>");
            int managementEnd = rootPom.indexOf("</pluginManagement>");
            int execution = rootPom.indexOf("<id>" + EXECUTION_ID + "</id>");
            assertThat(execution).isGreaterThan(-1);
            assertThat(management > -1 && execution > management && execution < managementEnd)
                    .as("the ban execution sits outside <pluginManagement>, so every module"
                            + " inherits it")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the audit surface cannot be exempted")
    class AuditSurface {

        @Test
        @DisplayName("eir-domain and eir-calc carry no override of the ban")
        void neverExempted() {
            // 05 s2.1 commits to exactly these two, and Maven cannot express "inheritable except
            // that these may not opt out". So it is asserted here instead. A future pom adding
            // <phase>none</phase> to either fails this test rather than quietly stopping the
            // check that keeps the audit surface readable as mathematics.
            for (String module : AUDIT_SURFACE) {
                assertThat(modulePoms)
                        .as("%s is a declared module", module)
                        .containsKey(module);
                assertThat(overridesTheBan(modulePoms.get(module)))
                        .as("%s overrides the framework ban; 05 s2.1 does not permit it and"
                                + " ADR-0010 does not either", module)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("neither declares a banned dependency directly, override or not")
        void noBannedCoordinates() {
            // Belt to the enforcer's braces, and it catches a case the enforcer would not: a
            // module that both adds Spring AND disables the check passes the build entirely.
            // Reading the pom sees both halves.
            for (String module : AUDIT_SURFACE) {
                String pom = modulePoms.get(module);
                for (String banned : List.of(
                        "org.springframework", "jakarta.persistence",
                        "com.fasterxml.jackson", "org.hibernate")) {
                    assertThat(pom)
                            .as("%s declares no dependency on %s", module, banned)
                            .doesNotContain("<groupId>" + banned);
                }
            }
        }
    }

    @Nested
    @DisplayName("every exemption names its authority")
    class Exemptions {

        @Test
        @DisplayName("a module that overrides the ban says why, in writing")
        void exemptionsAreJustified() {
            // The clause that makes the mechanism more than a convention. Without it, ADR-0010's
            // marker is a comment somebody remembered to write, and a comment nothing parses is
            // a comment.
            List<String> unjustified = new ArrayList<>();
            for (Map.Entry<String, String> module : modulePoms.entrySet()) {
                if (!overridesTheBan(module.getValue())) {
                    continue;
                }
                String pom = module.getValue();
                int marker = pom.indexOf(EXEMPTION_MARKER);
                boolean stated = marker > -1
                        && pom.substring(marker + EXEMPTION_MARKER.length())
                            .stripLeading().length() > 10;
                if (!stated) {
                    unjustified.add(module.getKey());
                }
            }
            assertThat(unjustified)
                    .as("modules disabling the framework ban with no '%s <authority>' line;"
                            + " ADR-0010 requires one so the boundary crossing appears in the"
                            + " diff a reviewer reads", EXEMPTION_MARKER)
                    .isEmpty();
        }

        @Test
        @DisplayName("exactly two modules are exempt, and both are runners rather than arithmetic")
        void exactlyTheIntendedModulesAreExempt() {
            // Pinned deliberately, and this is the edit the previous version of this test predicted:
            // "when eir-batch arrives this assertion changes in the same commit that adds the
            // dependency, which is the point." Two arrived together.
            //
            // eir-batch takes it under ADR-0007: Spring Batch is the RUNNER — partitioning, restart
            // from a failed step, a job repository — and none of that is arithmetic.
            // eir-persistence-jdbc takes it under ADR-0011: Flyway is a migration runner and the
            // PostgreSQL driver is a wire protocol.
            //
            // eir-persistence still has EARNED an exemption (04's schema wants an ORM) and still
            // has not taken one, because an exemption for a dependency nobody has added is the same
            // undocumented licence in the other direction. That remains the interesting entry in
            // this list: the one that is absent on purpose.
            List<String> exempt = modulePoms.entrySet().stream()
                    .filter(module -> overridesTheBan(module.getValue()))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            assertThat(exempt)
                    .as("if this fails, check the exemption is intended and update the list here;"
                            + " a third exemption should be a visible diff and a paragraph of"
                            + " justification, not a quiet addition")
                    .containsExactly("eir-batch", "eir-persistence-jdbc");
        }
    }
}
