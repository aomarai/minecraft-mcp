package dev.aomarai.minecraftmcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a secret-holding file without ever leaving it empty or cut short: the new content goes to an owner-only
 * temporary file in the same folder, which is then moved over the real one in a single step. A crash or a full disk
 * leaves the old file whole.
 */
final class AtomicFile {

    /** {@link Files#move}, replaceable so a test can fail the move or refuse an atomic one. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to, CopyOption... options) throws IOException;
    }

    private AtomicFile() {
    }

    static void write(Path file, String content) throws IOException {
        write(file, content, Files::move);
    }

    static void write(Path file, String content, Mover mover) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            // Owner-only before the secret is written, so it never sits in a file others can read.
            OwnerOnlyFile.prepare(temporary);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                mover.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                mover.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
