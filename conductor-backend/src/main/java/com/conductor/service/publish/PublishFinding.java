package com.conductor.service.publish;

/**
 * One thing the approval gate has to say about a Post: a blocker that stops it going out, or a warning a
 * human should see and may ignore.
 *
 * <p>Findings are what the gate's validators produce when asked to <em>inspect</em> rather than
 * <em>enforce</em>. The same list backs both the 422 a refused transition throws and the preflight
 * response an agent or the UI reads before attempting one, which is what keeps the two from disagreeing.
 *
 * @param severity whether this stops the transition
 * @param code     a stable, machine-readable name for the rule (e.g. {@code FIRE_TIME_TOO_SOON}); the
 *                 message may be reworded, the code may not
 * @param message  the human sentence, exactly as the 422 would carry it
 * @param targetId the publish target the finding is about, or null for a Post-level finding
 */
public record PublishFinding(Severity severity, String code, String message, String targetId) {

    public enum Severity { BLOCKER, WARNING }

    public static PublishFinding blocker(String code, String message) {
        return new PublishFinding(Severity.BLOCKER, code, message, null);
    }

    public static PublishFinding blocker(String code, String message, String targetId) {
        return new PublishFinding(Severity.BLOCKER, code, message, targetId);
    }

    public static PublishFinding warning(String code, String message, String targetId) {
        return new PublishFinding(Severity.WARNING, code, message, targetId);
    }

    public boolean blocks() {
        return severity == Severity.BLOCKER;
    }
}
