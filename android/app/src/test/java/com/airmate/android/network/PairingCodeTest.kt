package com.airmate.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {
    @Test fun parsesAirMatePairingURL() {
        assertEquals(
            PairingCode.Target("192.168.1.20", 48620),
            PairingCode.parse("airmate://pair?host=192.168.1.20&port=48620")
        )
    }

    @Test fun rejectsForeignOrInvalidCodes() {
        assertNull(PairingCode.parse("https://example.com"))
        assertNull(PairingCode.parse("airmate://pair?host=192.168.1.20&port=99999"))
    }
}
