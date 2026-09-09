package io.tenantlayer.check;

/**
 * One thing that is wrong, or might be, with how isolation is set up.
 *
 * @param severity how much it matters
 * @param subject  the table or role the finding is about
 * @param problem  what is wrong, in one line
 * @param fix      the statement or change that resolves it
 */
public record IsolationFinding(Severity severity, String subject, String problem, String fix) {

    public enum Severity {
        /** Isolation is not being enforced. Data is reachable across tenants. */
        NOT_ENFORCED,
        /** Enforced today, but one ordinary change removes it. */
        FRAGILE,
        /** Worth knowing, not urgent. */
        NOTE,
    }

    @Override
    public String toString() {
        return "%s  %s — %s%n    fix: %s".formatted(severity, subject, problem, fix);
    }
}
