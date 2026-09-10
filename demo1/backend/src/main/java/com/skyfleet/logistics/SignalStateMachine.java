package com.skyfleet.logistics;

import java.util.List;
import java.util.Map;

public final class SignalStateMachine {
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "DETECTED", Map.of("ACKNOWLEDGE", "ACKNOWLEDGED", "IGNORE", "IGNORED"),
            "ACKNOWLEDGED", Map.of("BEGIN_INVESTIGATION", "INVESTIGATING", "RESOLVE", "RESOLVED", "IGNORE", "IGNORED"),
            "INVESTIGATING", Map.of("RESOLVE", "RESOLVED", "IGNORE", "IGNORED")
    );

    private SignalStateMachine() {}

    public static String transition(String currentStatus, String commandType) {
        String next = TRANSITIONS.getOrDefault(currentStatus, Map.of()).get(commandType);
        if (next == null) throw new IllegalArgumentException("Signal 状态 " + currentStatus + " 不允许执行 " + commandType);
        return next;
    }

    public static List<String> allowedActions(String status) {
        return TRANSITIONS.getOrDefault(status, Map.of()).keySet().stream().sorted().toList();
    }

    public static boolean requiresAction(String status) {
        return !List.of("RESOLVED", "IGNORED").contains(status);
    }
}
