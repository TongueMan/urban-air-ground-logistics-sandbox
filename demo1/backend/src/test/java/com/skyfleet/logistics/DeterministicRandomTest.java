package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeterministicRandomTest {
    @Test void sameSeedAndDomainProduceSameStream() {
        long seed = DeterministicRandom.derive("9527", "attempt/1/air");
        DeterministicRandom first = new DeterministicRandom(seed);
        DeterministicRandom second = new DeterministicRandom(seed);
        for (int index = 0; index < 100; index++) assertEquals(first.nextLong(), second.nextLong());
    }

    @Test void domainsRemainIndependent() {
        assertNotEquals(DeterministicRandom.derive("9527", "targets"), DeterministicRandom.derive("9527", "traffic"));
    }
}
