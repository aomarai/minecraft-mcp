package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryRecordsTest {

    // ---- RegistryQuery ----

    @Test
    void registryQueryNormalizesAndKeepsItsFields() {
        RegistryQuery query = new RegistryQuery("block", "  Gold ", 3, 50);

        assertEquals("minecraft:block", query.registry());
        assertEquals("Gold", query.search());
        assertEquals(3, query.offset());
        assertEquals(50, query.limit());
    }

    @Test
    void registryQuerySearchDefaultsToEverything() {
        assertEquals("", new RegistryQuery("minecraft:item", null, 0, 1).search());
    }

    @Test
    void registryQueryBoundsAreExact() {
        assertDoesNotThrow(() -> new RegistryQuery("minecraft:block", "", 0, 1));
        assertDoesNotThrow(() -> new RegistryQuery("minecraft:block", "", 0, RegistryQuery.MAX_LIMIT));
        assertEquals("limit must be between 1 and 200",
                assertThrows(IllegalArgumentException.class, () -> new RegistryQuery("minecraft:block", "", 0, 0)).getMessage());
        assertEquals("limit must be between 1 and 200",
                assertThrows(IllegalArgumentException.class, () -> new RegistryQuery("minecraft:block", "", 0, 201)).getMessage());
        assertEquals("offset must not be negative",
                assertThrows(IllegalArgumentException.class, () -> new RegistryQuery("minecraft:block", "", -1, 1)).getMessage());
        assertDoesNotThrow(() -> new RegistryQuery("minecraft:block", "", Integer.MAX_VALUE, 1));
    }

    @Test
    void registryQueryRejectsABadRegistryId() {
        assertThrows(IllegalArgumentException.class, () -> new RegistryQuery("Not Valid", "", 0, 1));
        assertThrows(NullPointerException.class, () -> new RegistryQuery(null, "", 0, 1));
    }

    @Test
    void registryPageCopiesItsIds() {
        List<String> source = new ArrayList<>(List.of("minecraft:stone"));
        RegistryPage page = new RegistryPage("minecraft:block", source, 1, 0, false);
        source.add("minecraft:dirt");

        assertEquals(List.of("minecraft:stone"), page.ids());
        var ids2 = page.ids();
        assertThrows(UnsupportedOperationException.class, () -> ids2.add("x"));
    }

    // ---- EntityQuery ----

    @Test
    void entityQueryNormalizesTheTypeAndKeepsItsFields() {
        EntityQuery query = new EntityQuery("minecraft:overworld", 1.5, 64, -2.5, 10, " zombie ", 20);

        assertEquals("minecraft:zombie", query.type());
        assertEquals(1.5, query.x());
        assertEquals(64, query.y());
        assertEquals(-2.5, query.z());
        assertEquals(10, query.radius());
        assertEquals(20, query.limit());
    }

    @Test
    void entityQueryTypeDefaultsToAnyType() {
        assertEquals("", new EntityQuery("d", 0, 0, 0, 5, null, 1).type());
        assertEquals("", new EntityQuery("d", 0, 0, 0, 5, "   ", 1).type());
        assertEquals("", new EntityQuery("d", 0, 0, 0, 5, "", 1).type());
    }

    @Test
    void entityQueryRadiusBoundsAreExactAndNanIsRejected() {
        assertDoesNotThrow(() -> new EntityQuery("d", 0, 0, 0, 1, "", 1));
        assertDoesNotThrow(() -> new EntityQuery("d", 0, 0, 0, 64, "", 1));
        for (double bad : new double[]{0.99, 64.01, 0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals("radius must be between 1 and 64",
                    assertThrows(IllegalArgumentException.class, () -> new EntityQuery("d", 0, 0, 0, bad, "", 1)).getMessage(),
                    String.valueOf(bad));
        }
    }

    @Test
    void entityQueryLimitBoundsAreExact() {
        assertDoesNotThrow(() -> new EntityQuery("d", 0, 0, 0, 5, "", 1));
        assertDoesNotThrow(() -> new EntityQuery("d", 0, 0, 0, 5, "", EntityQuery.MAX_LIMIT));
        assertEquals("limit must be between 1 and 200",
                assertThrows(IllegalArgumentException.class, () -> new EntityQuery("d", 0, 0, 0, 5, "", 0)).getMessage());
        assertEquals("limit must be between 1 and 200",
                assertThrows(IllegalArgumentException.class, () -> new EntityQuery("d", 0, 0, 0, 5, "", 201)).getMessage());
    }

    @Test
    void entityQueryRequiresADimensionAndAWellFormedType() {
        assertThrows(NullPointerException.class, () -> new EntityQuery(null, 0, 0, 0, 5, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new EntityQuery("d", 0, 0, 0, 5, "Not Valid", 1));
    }

    // ---- WorldState / Permissions ----

    @Test
    void worldStateSortsAndFreezesItsGameRules() {
        java.util.Map<String, String> source = new java.util.HashMap<>();
        source.put("zeta", "1");
        source.put("alpha", "2");
        WorldState state = new WorldState("minecraft:overworld", 1L, 2L, false, true, "hard", source);
        source.put("late", "3");

        assertEquals(List.of("alpha", "zeta"), List.copyOf(state.gameRules().keySet()));
        var gameRules2 = state.gameRules();
        assertThrows(UnsupportedOperationException.class, () -> gameRules2.put("x", "y"));
        assertEquals(1L, state.gameTime());
        assertEquals(2L, state.dayTime());
        assertFalse(state.raining());
        assertTrue(state.thundering());
        assertEquals("hard", state.difficulty());
    }

    @Test
    void permissionsCopyTheirLists() {
        List<Permissions.Operator> ops = new ArrayList<>(List.of(new Permissions.Operator("Alex", "u", 4)));
        List<Permissions.Whitelisted> white = new ArrayList<>(List.of(new Permissions.Whitelisted("Steve", "v")));
        Permissions permissions = new Permissions(ops, true, white);
        ops.clear();
        white.clear();

        assertEquals(1, permissions.operators().size());
        assertEquals(1, permissions.whitelist().size());
        assertTrue(permissions.whitelistEnabled());
        var operators2 = permissions.operators();
        assertThrows(UnsupportedOperationException.class, () -> operators2.clear());
        var whitelist2 = permissions.whitelist();
        assertThrows(UnsupportedOperationException.class, () -> whitelist2.clear());
    }

    @Test
    void inventoryCopiesItsStacks() {
        List<Inventory.Stack> source = new ArrayList<>(List.of(new Inventory.Stack(0, "minecraft:stone", 3)));
        Inventory inventory = new Inventory("Steve", source);
        source.clear();

        assertEquals("Steve", inventory.player());
        assertEquals(List.of(new Inventory.Stack(0, "minecraft:stone", 3)), inventory.stacks());
        var stacks2 = inventory.stacks();
        assertThrows(UnsupportedOperationException.class, () -> stacks2.clear());
    }

    private static String entityName(String name) {
        return new EntityList.EntityInfo("minecraft:pig", "u", name, 0, 0, 0).name();
    }

    @Test
    void entityNamesAreCleanedAndCutAtTheCap() {
        assertEquals("Bob rm -rf", entityName("Bob\nrm\t-rf"));
        assertEquals("a b c", entityName("a\u0000b\u001bc"));
        assertEquals("", entityName(null));
        String atCapMinusOne = "n".repeat(EntityList.EntityInfo.MAX_NAME - 1);
        String atCap = "n".repeat(EntityList.EntityInfo.MAX_NAME);
        assertEquals(atCapMinusOne, entityName(atCapMinusOne));
        assertEquals(atCap, entityName(atCap));
        assertEquals(atCap + "...", entityName(atCap + "n"));
    }

    @Test
    void entityListCopiesItsEntities() {
        List<EntityList.EntityInfo> source = new ArrayList<>(List.of(new EntityList.EntityInfo("minecraft:pig", "u", "Pig", 1, 2, 3)));
        EntityList list = new EntityList("minecraft:overworld", source, false);
        source.clear();

        assertEquals(1, list.entities().size());
        assertEquals("minecraft:pig", list.entities().get(0).type());
        var entities2 = list.entities();
        assertThrows(UnsupportedOperationException.class, () -> entities2.clear());
    }
}
