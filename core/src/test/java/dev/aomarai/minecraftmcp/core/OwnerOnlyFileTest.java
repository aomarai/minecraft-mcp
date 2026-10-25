package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OwnerOnlyFileTest {

    @TempDir
    Path dir;

    static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    @Test
    void prepareCreatesParentsAndAnEmptyOwnerOnlyFile() throws IOException {
        Path file = dir.resolve("a").resolve("b").resolve("secret.txt");

        OwnerOnlyFile.prepare(file);

        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.size(file));
        assumeTrue(posix());
        assertEquals("rw-------", mode(file));
    }

    @Test
    void prepareKeepsExistingContentAndTightensPermissions() throws IOException {
        Path file = dir.resolve("secret.txt");
        Files.writeString(file, "keep me");
        assumeTrue(posix());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxrwxrwx"));

        OwnerOnlyFile.prepare(file);

        assertEquals("keep me", Files.readString(file));
        assertEquals("rw-------", mode(file));
    }

    @Test
    void restrictRemovesAccessForEveryoneButTheOwner() throws IOException {
        Path file = dir.resolve("secret.txt");
        Files.writeString(file, "x");
        assumeTrue(posix());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        OwnerOnlyFile.restrict(file);
        assertEquals("rw-------", mode(file));
        OwnerOnlyFile.restrict(file);
        assertEquals("rw-------", mode(file), "idempotent");
    }
}
