package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemoSessionDeltaBufferTest {
    @Test
    void replaysOnlyContinuousEventsAfterLastSeenRevision() {
        DemoSession session = new DemoSession("S1", "visitor", "RUNNING", "mission", "1.0.0");
        session.revision.set(1);
        session.recordDelta(1, "mission-delta", Map.of("revision", 1));
        session.revision.set(2);
        session.recordDelta(2, "track-delta", Map.of("revision", 2));

        var replay = session.deltasAfter(1);
        assertNotNull(replay);
        assertEquals(1, replay.size());
        assertEquals(2, replay.get(0).revision());
        assertEquals("track-delta", replay.get(0).eventType());
        assertTrue(session.deltasAfter(2).isEmpty());
    }

    @Test
    void rejectsUnknownFutureAndMissingRevisions() {
        DemoSession session = new DemoSession("S1", "visitor", "RUNNING", "mission", "1.0.0");
        session.revision.set(3);
        session.recordDelta(3, "mission-delta", Map.of("revision", 3));

        assertNull(session.deltasAfter(1));
        assertNull(session.deltasAfter(4));
    }

    @Test
    void expiresEventsOutsideBoundedWindow() {
        DemoSession session = new DemoSession("S1", "visitor", "RUNNING", "mission", "1.0.0");
        for (long revision = 1; revision <= 520; revision++) {
            session.revision.set(revision);
            session.recordDelta(revision, "track-delta", Map.of("revision", revision));
        }
        assertNull(session.deltasAfter(1));
        assertEquals(2, session.deltasAfter(518).size());
    }
}
