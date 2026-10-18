package com.codeoj.codeojbackendmodel.model.codesandbox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextOutputComparatorTest {
    @Test void acceptsCrLfAndOneFinalNewline() {
        assertTrue(TextOutputComparator.matches("1\n2", "1\r\n2\r\n"));
    }
    @Test void preservesSpacesAndTabs() {
        assertFalse(TextOutputComparator.matches("1", " 1"));
        assertFalse(TextOutputComparator.matches("1", "1 "));
        assertFalse(TextOutputComparator.matches("1 2", "1\t2"));
    }
    @Test void preservesAdditionalBlankLinesAndCase() {
        assertFalse(TextOutputComparator.matches("1", "1\n\n"));
        assertFalse(TextOutputComparator.matches("yes", "YES"));
        assertFalse(TextOutputComparator.matches("1", "1\r"));
    }
    @Test void emptyOutputAndMissingOutputAreDifferent() {
        assertTrue(TextOutputComparator.matches("", "\n"));
        assertThrows(IllegalArgumentException.class, () -> TextOutputComparator.matches("", null));
    }
}
