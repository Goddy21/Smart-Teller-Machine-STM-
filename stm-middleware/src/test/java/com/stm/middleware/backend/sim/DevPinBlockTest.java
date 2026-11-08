package com.stm.middleware.backend.sim;

import com.stm.middleware.stm.core.StmException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DevPinBlockTest {
    @Test
    void roundTrip() {
        assertEquals("042580FFFFFFFFFF", DevPinBlock.encode("2580"));
        assertEquals("2580", DevPinBlock.decode("042580FFFFFFFFFF"));
        assertEquals("123456", DevPinBlock.decode(DevPinBlock.encode("123456")));
    }

    @Test
    void rejectsEncryptedOrMalformedBlocks() {
        assertThrows(StmException.class, () -> DevPinBlock.decode("8F2C1A9D4E7B3F60"));
        assertThrows(StmException.class, () -> DevPinBlock.decode("0425801FFFFFFFFF"));
        assertThrows(StmException.class, () -> DevPinBlock.decode(null));
    }

    @Test
    void policy() {
        assertEquals("SEQUENTIAL_DIGITS", DevPinBlock.policyViolation("1234"));
        assertEquals("SEQUENTIAL_DIGITS", DevPinBlock.policyViolation("9876"));
        assertEquals("REPEATED_DIGITS", DevPinBlock.policyViolation("0000"));
        assertNull(DevPinBlock.policyViolation("2580"));
    }
}
