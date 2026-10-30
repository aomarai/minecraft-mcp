package dev.aomarai.minecraftmcp.core;

import java.time.Instant;
import java.util.Map;

/**
 * One audit record. Every tool call produces a {@code decision} entry before
 * anything runs, and a {@code result} entry after an allowed call finishes.
 *
 * @param time      when the entry was recorded
 * @param phase     {@code decision} or {@code result}
 * @param tool      tool name
 * @param arguments call arguments (the log redacts secrets and truncates long values)
 * @param decision  {@code allow}, {@code deny} or {@code confirm}
 * @param detail    the policy reason for a decision, or a result summary
 * @param timing    what the call took, for a {@code result} entry; {@link CallTiming.Result#NONE} otherwise
 */
public record AuditEntry(
        Instant time,
        String phase,
        String tool,
        Map<String, Object> arguments,
        String decision,
        String detail,
        CallTiming.Result timing) {

    public AuditEntry(Instant time, String phase, String tool, Map<String, Object> arguments, String decision,
                      String detail) {
        this(time, phase, tool, arguments, decision, detail, CallTiming.Result.NONE);
    }

    public static final String PHASE_DECISION = "decision";
    public static final String PHASE_RESULT = "result";
}
