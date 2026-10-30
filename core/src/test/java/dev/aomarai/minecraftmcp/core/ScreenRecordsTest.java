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
    void anIdIsTheIndexTheRoleTheScreenGenerationAndAHashOfTheLabel() {
        assertEquals("3:text_field:5:77df1a9a", ScreenElement.idOf(3, ScreenElement.Role.TEXT_FIELD, 5, "Cancel"));
        assertEquals("0:button:0:0", ScreenElement.idOf(0, ScreenElement.Role.BUTTON, 0, ""));
        assertEquals("text_field", ScreenElement.Role.TEXT_FIELD.json());
    }

    @Test
    void theSameScreenListedTwiceGivesTheSameIds() {
        assertEquals(ScreenElement.idOf(1, ScreenElement.Role.BUTTON, 2, "Cancel"),
                ScreenElement.idOf(1, ScreenElement.Role.BUTTON, 2, "Cancel"));
    }

    @Test
    void aLabelThatIsCleanedHashesTheSameAsItsCleanedForm() {
        assertEquals(ScreenElement.idOf(1, ScreenElement.Role.BUTTON, 2, "Cancel me"),
                ScreenElement.idOf(1, ScreenElement.Role.BUTTON, 2, "Cancel\nme"));
    }

    private static List<ScreenElement> screen(long generation, String first, String second) {
        return List.of(
                new ScreenElement(ScreenElement.idOf(0, ScreenElement.Role.BUTTON, generation, first),
                        ScreenElement.Role.BUTTON, first, 0, 0, 1, 1, true, false),
                new ScreenElement(ScreenElement.idOf(1, ScreenElement.Role.BUTTON, generation, second),
                        ScreenElement.Role.BUTTON, second, 0, 0, 1, 1, true, false));
    }

    @Test
    void anIdResolvesToItsPositionOnTheScreenItCameFrom() {
        List<ScreenElement> listed = screen(4, "Delete", "Cancel");

        assertEquals(0, ScreenElement.resolve(listed, listed.get(0).id()));
        assertEquals(1, ScreenElement.resolve(screen(4, "Delete", "Cancel"), listed.get(1).id()));
    }

    @Test
    void anIdFromAnOlderScreenGenerationIsRefused() {
        String id = screen(4, "Delete", "Cancel").get(1).id();

        var arg = screen(5, "Delete", "Cancel");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ScreenElement.resolve(arg, id));

        assertEquals("no element " + id + " on this screen; it may have changed, call get_screen again", e.getMessage());
    }

    @Test
    void anIdWhoseLabelChangedIsRefused() {
        String id = screen(4, "Delete", "Cancel").get(1).id();

        var arg2 = screen(4, "Delete", "Keep");
        assertThrows(IllegalStateException.class, () -> ScreenElement.resolve(arg2, id));
    }

    @Test
    void anIdThatPointsOutsideTheScreenOrIsMalformedIsRefused() {
        List<ScreenElement> listed = screen(4, "Delete", "Cancel");

        for (String id : new String[]{"2:button:4:0", "-1:button:4:0", ":button", "x:button:4:0", "", "button"}) {
            assertThrows(IllegalStateException.class, () -> ScreenElement.resolve(listed, id), id);
        }
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
        var elements2 = snapshot.elements();
        var arg3 = element("b");
        assertThrows(UnsupportedOperationException.class, () -> elements2.add(arg3));

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
