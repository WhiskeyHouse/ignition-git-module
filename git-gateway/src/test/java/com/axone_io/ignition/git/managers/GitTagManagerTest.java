package com.axone_io.ignition.git.managers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GitTagManagerTest {

    @Test
    public void encodeFsName_leavesSafeNamesUnchanged() {
        assertEquals("Tank1_Level", GitTagManager.encodeFsName("Tank1_Level"));
        assertEquals("ns2_v1.0", GitTagManager.encodeFsName("ns2_v1.0"));
        assertEquals("foo-bar baz", GitTagManager.encodeFsName("foo-bar baz"));
    }

    @Test
    public void encodeFsName_escapesWindowsReservedChars() {
        // The actual failing case from production
        assertEquals("_0%3A0%3Awhk-development-gw",
                GitTagManager.encodeFsName("_0:0:whk-development-gw"));
        // Each Windows-reserved char must be escaped
        assertEquals("a%3Cb%3Ec%3Ad%22e%2Ff%5Cg%7Ch%3Fi%2Aj",
                GitTagManager.encodeFsName("a<b>c:d\"e/f\\g|h?i*j"));
    }

    @Test
    public void encodeFsName_escapesPercentItself() {
        // Percent must be escaped first so decoding is unambiguous
        assertEquals("100%25", GitTagManager.encodeFsName("100%"));
        assertEquals("%25%3A", GitTagManager.encodeFsName("%:"));
    }

    @Test
    public void encodeFsName_handlesNullAndEmpty() {
        assertNull(GitTagManager.encodeFsName(null));
        assertEquals("", GitTagManager.encodeFsName(""));
    }

    @Test
    public void decodeFsName_reversesEncoding() {
        String[] originals = {
                "_0:0:whk-development-gw",
                "ns=2;s=Foo:Bar",
                "100%",
                "a<b>c:d\"e/f\\g|h?i*j",
                "Tank1_Level",
                ""
        };
        for (String original : originals) {
            String roundtrip = GitTagManager.decodeFsName(GitTagManager.encodeFsName(original));
            assertEquals("round-trip failed for: " + original, original, roundtrip);
        }
    }

    @Test
    public void decodeFsName_leavesUnknownPercentsIntact() {
        // Legacy names with bare % that aren't valid hex escapes should pass through
        assertEquals("not%xxhex", GitTagManager.decodeFsName("not%xxhex"));
        assertEquals("trailing%", GitTagManager.decodeFsName("trailing%"));
        assertEquals("trailing%2", GitTagManager.decodeFsName("trailing%2"));
    }

    @Test
    public void decodeFsName_handlesNullAndEmpty() {
        assertNull(GitTagManager.decodeFsName(null));
        assertEquals("", GitTagManager.decodeFsName(""));
    }
}
