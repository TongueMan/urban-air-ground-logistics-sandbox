package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalStateMachineTest {
    @Test
    void followsTheAuditableWorkflow() {
        assertThat(SignalStateMachine.transition("DETECTED", "ACKNOWLEDGE")).isEqualTo("ACKNOWLEDGED");
        assertThat(SignalStateMachine.transition("ACKNOWLEDGED", "BEGIN_INVESTIGATION")).isEqualTo("INVESTIGATING");
        assertThat(SignalStateMachine.transition("INVESTIGATING", "RESOLVE")).isEqualTo("RESOLVED");
        assertThat(SignalStateMachine.requiresAction("RESOLVED")).isFalse();
    }

    @Test
    void rejectsSkippedOrTerminalTransitions() {
        assertThatThrownBy(() -> SignalStateMachine.transition("DETECTED", "RESOLVE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalStateMachine.transition("RESOLVED", "ACKNOWLEDGE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyActionsAllowedFromTheCurrentState() {
        assertThat(SignalStateMachine.allowedActions("DETECTED")).containsExactly("ACKNOWLEDGE", "IGNORE");
        assertThat(SignalStateMachine.allowedActions("INVESTIGATING")).containsExactly("IGNORE", "RESOLVE");
        assertThat(SignalStateMachine.allowedActions("IGNORED")).isEmpty();
    }
}
