package dev.agentretry.demo;

/** Typed failures determine application decisions rather than broad catch-and-retry. */
final class Problems {
    private Problems() { }
    static class Permanent extends RuntimeException {
        final String reason;
        Permanent(String reason) { super(reason); this.reason = reason; }
    }
    static final class Contract extends Permanent { Contract(String reason) { super(reason); } }
    static final class Policy extends Permanent { Policy(String reason) { super(reason); } }
    static final class Transient extends RuntimeException { Transient(String reason) { super(reason); } }
    /** Test-only analogue of a process disappearing. It intentionally bypasses RuntimeException handlers. */
    static final class Interrupted extends Error { Interrupted(String reason) { super(reason); } }
}
