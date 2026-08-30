package com.crisil.eir.policy.access;

import com.crisil.eir.domain.FourEyes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which identities hold which roles — the grant table, and the only thing that turns an asserted
 * identity into a {@link Principal}.
 *
 * <p><b>This is not an identity provider, and nothing here authenticates.</b> 07 § 7 specifies
 * OAuth2 client credentials over TLS 1.3, with an external secret manager; none of that is in this
 * repository, and a register that quietly implied otherwise would be worse than the honest absence
 * it replaces. What this holds is the <em>authorisation</em> half: given a name somebody has already
 * been authenticated as, what may they do. In a production deployment the names arrive from the
 * token the upstream terminator validated; here they arrive from a request header, and every
 * response that uses one says so.
 *
 * <p><b>Lookup is by {@code FourEyes.identityKey}.</b> Not by the raw string. A register keyed on
 * the raw string would grant {@code "Ops.Lead"} a role and refuse {@code "ops.lead"}, so the same
 * person would be two principals — one of whom could then approve the other's work without ever
 * tripping the segregation check. That is the identical defect {@code FourEyes} was extracted to
 * close, one layer up.
 *
 * <p><b>There is no default role.</b> An identity the register does not hold is refused
 * ({@link AccessRefusal#UNKNOWN_IDENTITY}), never granted a read-only fallback. A default role is
 * how an unmapped caller acquires capabilities nobody granted it, and it is the same failure the fee
 * rule set refuses when it quarantines an unmapped fee code instead of defaulting it.
 */
public final class RoleRegister {

    private final Map<String, Principal> byKey;

    private RoleRegister(Map<String, Principal> byKey) {
        this.byKey = Collections.unmodifiableMap(byKey);
    }

    /**
     * A register over the principals given, keyed by {@code FourEyes.identityKey}.
     *
     * <p>Two principals whose identities fold to the same key are refused rather than merged or
     * last-one-wins. Merging would union their roles, which is a widening nobody approved;
     * last-one-wins would make the grant a function of iteration order. Either way one row of a
     * grant table would silently not mean what it says, and this is the table every other control
     * in the model reads.
     */
    public static RoleRegister of(List<Principal> principals) {
        Objects.requireNonNull(principals, "principals");
        Map<String, Principal> byKey = new LinkedHashMap<>();
        for (Principal principal : principals) {
            Objects.requireNonNull(principal, "principal");
            String key = FourEyes.identityKey(principal.identity());
            Principal clash = byKey.put(key, principal);
            if (clash != null) {
                throw new IllegalArgumentException(
                    "the role register holds '" + clash.identity() + "' and '"
                        + principal.identity() + "', which are the same identity; one identity"
                        + " with two grants means the roles it holds depend on which row is read");
            }
        }
        return new RoleRegister(byKey);
    }

    /** A register over the principals given. */
    public static RoleRegister of(Principal... principals) {
        return of(List.of(Objects.requireNonNull(principals, "principals")));
    }

    /**
     * The principal an asserted identity resolves to, or empty.
     *
     * <p>Empty for a blank or absent identity too — {@link AccessControl} tells the two apart and
     * reports them as different refusals, because "you sent nothing" and "we do not know you" send
     * their readers to different teams.
     */
    public Optional<Principal> resolve(String assertedIdentity) {
        if (assertedIdentity == null || assertedIdentity.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(FourEyes.identityKey(assertedIdentity)));
    }

    /** Every principal in the register, ordered by identity key so a listing is stable. */
    public List<Principal> principals() {
        Map<String, Principal> ordered = new TreeMap<>(byKey);
        return List.copyOf(new ArrayList<>(ordered.values()));
    }

    /** How many identities the register holds. */
    public int size() {
        return byKey.size();
    }

    /**
     * Every identity in the register whose grant puts both halves of a maker–checker pair on one
     * person — 07 § 7's segregation of duties, as a standing property of the table.
     *
     * <p><b>Why the whole table and not just the identity in front of you.</b> Because the finding
     * is about the grant, not about a request: an identity that holds {@code BATCH_OPERATOR} and
     * {@code APPROVER} is a segregation exception whether or not it ever exercises both, and
     * {@link AccessControl} refusing the individual act protects the close while leaving the
     * exception in place. 07 § 4.1's premise is that a control is asserted <em>and reported</em>.
     * This is the report, and it is what {@code /api/access/roles} publishes so an operator sees
     * the combination before an auditor does.
     *
     * @return identity to the pairs it holds both halves of; empty when the table is clean
     */
    public Map<String, List<String>> segregationExceptions() {
        Map<String, List<String>> found = new TreeMap<>();
        for (Principal principal : principals()) {
            List<String> pairs = principal.toxicCombinations();
            if (!pairs.isEmpty()) {
                found.put(principal.identity(), pairs);
            }
        }
        return Collections.unmodifiableMap(found);
    }
}
