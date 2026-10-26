package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenRecordsTest {

    private static ScreenElement element(String label) {
        return new ScreenElement("0:button", ScreenElement.Role.BUTTON, label, 0, 0, 1, 1, true, false);
    }

    @Test
    void anIdIsTheIndexAndTheRoleName() {
        assertEquals("3:text_field", ScreenElement.idOf(3, ScreenElement.Role.TEXT_FIELD));
        assertEquals("0:button", ScreenElement.idOf(0, ScreenElement.Role.BUTTON));
        assertEquals("text_field", ScreenElement.Role.TEXT_FIELD.json());
    }

    @Test
    void anElementNeedsAnIdAndARole() {
        assertThrows(NullPointerException.class,
                () -> new ScreenElement(null, ScreenElement.Role.BUTTON, "a", 0, 0, 1, 1, true, false));
        assertThrows(NullPointerException.class, () -> new ScreenElement("0:button", null, "a", 0, 0, 1, 1, true, false));
        assertEquals("id must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new ScreenElement(" ", ScreenElement.Role.BUTTON, "a", 0, 0, 1, 1, true, false)).getMessage());
    }

    @Test
    void anElementLabelIsCleanedAndCut() {
        assertEquals("a b c", element("a\nb\tc").label());
        assertEquals("", element(null).label());
        assertEquals("x".repeat(256), element("x".repeat(256)).label());
        assertEquals("x".repeat(256) + "...", element("x".repeat(257)).label());
    }

    @Test
    void aSnapshotCleansItsTextCopiesItsListAndIsBounded() {
        List<ScreenElement> elements = new ArrayList<>(List.of(element("a")));
        ScreenSnapshot snapshot = new ScreenSnapshot(" T\n ", "Ti\ntle", " O ", elements);
        elements.clear();

        assertEquals("T", snapshot.screen());
        assertEquals("Ti tle", snapshot.title());
        assertEquals("O", snapshot.overlay());
        assertEquals(1, snapshot.elements().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.elements().add(element("b")));

        assertEquals("x".repeat(128), new ScreenSnapshot("x".repeat(128), "", "", List.of()).screen());
        assertEquals("x".repeat(128) + "...", new ScreenSnapshot("x".repeat(129), "", "", List.of()).screen());
        assertEquals("x".repeat(128), new ScreenSnapshot("", "", "x".repeat(128), List.of()).overlay());
        assertEquals("x".repeat(128) + "...", new ScreenSnapshot("", "", "x".repeat(129), List.of()).overlay());
        assertEquals("x".repeat(256), new ScreenSnapshot("", "x".repeat(256), "", List.of()).title());
        assertEquals("x".repeat(256) + "...", new ScreenSnapshot("", "x".repeat(257), "", List.of()).title());
    }

    @Test
    void aSnapshotHoldsAtMostThreeHundredElements() {
        List<ScreenElement> max = java.util.Collections.nCopies(ScreenSnapshot.MAX_ELEMENTS, element("a"));
        assertDoesNotThrow(() -> new ScreenSnapshot("", "", "", max));

        List<ScreenElement> over = java.util.Collections.nCopies(ScreenSnapshot.MAX_ELEMENTS + 1, element("a"));
        assertEquals("a screen lists at most 300 elements, got 301",
                assertThrows(IllegalArgumentException.class, () -> new ScreenSnapshot("", "", "", over)).getMessage());
        assertThrows(NullPointerException.class, () -> new ScreenSnapshot("", "", "", null));
    }
}
