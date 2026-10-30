package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFileTest {

    @TempDir
    Path dir;

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void writesTheContentAndLeavesNoTemporaryFile() throws IOException {
        Path file = dir.resolve("config").resolve("a.json");

        AtomicFile.write(file, "new");

        assertEquals("new", Files.readString(file));
        assertEquals(List.of(file), Files.list(dir.resolve("config")).toList());
    }

    @Test
    void replacesAnExistingFileWhole() throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "a much longer old content that must not survive");

        AtomicFile.write(file, "new");

        assertEquals("new", Files.readString(file));
    }

    @Test
    void aFailedMoveLeavesTheOldFileIntactAndNoTemporaryFileBehind() throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "old");

        AtomicFile.Mover failingMove = (from, to, options) -> {
            assertTrue(Files.exists(from), "the temporary file exists when the move fails");
            throw new IOException("disk full");
        };

        IOException failure = assertThrows(IOException.class, () -> AtomicFile.write(file, "new", failingMove));

        assertEquals("disk full", failure.getMessage());
        assertEquals("old", Files.readString(file));
        assertEquals(List.of(file), Files.list(dir).toList());
    }

    @Test
    void movesFromTheSameFolderAtomicallyFirst() throws IOException {
        Path file = dir.resolve("a.json");
        List<Path> from = new ArrayList<>();
        List<Set<CopyOption>> options = new ArrayList<>();

        AtomicFile.write(file, "new", (source, target, o) -> {
            from.add(source);
            options.add(Set.of(o));
            assertEquals(file, target);
            Files.move(source, target, o);
        });

        assertEquals(1, from.size());
        assertEquals(dir, from.get(0).getParent(), "same folder, so the move is a rename");
        assertEquals(Set.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), options.get(0));
    }

    @Test
    void fallsBackToAPlainReplaceWhereAtomicMovesAreNotSupported() throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "old");
        List<Set<CopyOption>> options = new ArrayList<>();

        AtomicFile.write(file, "new", (source, target, o) -> {
            options.add(Set.of(o));
            if (Set.of(o).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "no");
            }
            Files.move(source, target, o);
        });

        assertEquals("new", Files.readString(file));
        assertEquals(List.of(Set.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                Set.of(StandardCopyOption.REPLACE_EXISTING)), options);
        assertEquals(List.of(file), Files.list(dir).toList());
    }

    @Test
    void theTemporaryFileIsOwnerOnlyBeforeTheContentIsWrittenAndTheResultStaysSo() throws IOException {
        assumeTrue(posix());
        Path file = dir.resolve("a.json");
        Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Set<PosixFilePermission> atMove = EnumSet.noneOf(PosixFilePermission.class);

        AtomicFile.write(file, "secret", (source, target, o) -> {
            atMove.addAll(Files.getPosixFilePermissions(source));
            Files.move(source, target, o);
        });

        assertEquals(ownerOnly, atMove);
        assertEquals(ownerOnly, Files.getPosixFilePermissions(file));
    }
}
