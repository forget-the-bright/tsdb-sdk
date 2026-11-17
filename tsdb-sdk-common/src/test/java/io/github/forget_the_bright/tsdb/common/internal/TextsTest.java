package io.github.forget_the_bright.tsdb.common.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextsTest {

    @Test
    void trimToNullKeepsOnlyMeaningfulText() {
        assertNull(Texts.trimToNull(null));
        assertNull(Texts.trimToNull(""));
        assertNull(Texts.trimToNull("   \t\n"));
        assertEquals("abc", Texts.trimToNull("  abc \n"));
    }

    @Test
    void abbreviateKeepsBoundaryAndAppendsEllipsis() {
        String exact = repeat('x', 512);
        assertEquals(exact, Texts.abbreviate(exact));
        assertEquals(repeat('x', 512) + "...", Texts.abbreviate(repeat('x', 513)));
        assertNull(Texts.abbreviate(null));
        assertThrows(IllegalArgumentException.class, () -> Texts.abbreviate("x", -1));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }
}
