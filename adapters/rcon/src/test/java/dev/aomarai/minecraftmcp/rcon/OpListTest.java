package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpListTest {

    @TempDir
    Path dir;

    @Test
    void aServerWithNoOpsFileHasNoOperators() throws IOException {
        assertEquals(Set.of(), OpList.names(dir.resolve("ops.json")));
    }

    @Test
    void anEmptyListHasNoOperators() throws IOException {
        Path file = dir.resolve("ops.json");
        Files.writeString(file, "[]");

        assertEquals(Set.of(), OpList.names(file));
    }

    @Test
    void operatorsAreReadByNameInLowerCase() throws IOException {
        Path file = dir.resolve("ops.json");
        Files.writeString(file, "[{\"uuid\":\"u1\",\"name\":\"Steve\",\"level\":4,\"bypassesPlayerLimit\":false},"
                + "{\"uuid\":\"u2\",\"name\":\"ALEX_2\",\"level\":2}]");

        assertEquals(Set.of("steve", "alex_2"), OpList.names(file));
    }

    @Test
    void anEntryWithoutAStringNameIsIgnored() throws IOException {
        Path file = dir.resolve("ops.json");
        Files.writeString(file, "[{\"uuid\":\"u1\"},{\"name\":42},{\"name\":\"Sam\"}]");

        assertEquals(Set.of("sam"), OpList.names(file));
    }

    @Test
    void aFileThatIsNotAListOfEntriesIsAnError() throws IOException {
        Path file = dir.resolve("ops.json");
        Files.writeString(file, "{\"not\":\"a list\"}");

        assertThrows(IOException.class, () -> OpList.names(file));
    }

    @Test
    void aFileThatIsNotJsonIsAnError() throws IOException {
        Path file = dir.resolve("ops.json");
        Files.writeString(file, "this is not json");

        assertThrows(IOException.class, () -> OpList.names(file));
    }
}
