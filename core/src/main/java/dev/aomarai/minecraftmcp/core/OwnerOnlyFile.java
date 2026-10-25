package dev.aomarai.minecraftmcp.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Files that hold secrets or sensitive records (the config with its token, the audit log) are
 * readable and writable by their owner only: mode {@code 0600} on POSIX, best effort where the
 * file system has no such modes.
 */
final class OwnerOnlyFile {

    private OwnerOnlyFile() {
    }

    /**
     * Makes sure {@code file} and its parent directories exist and that the file is owner-only,
     * without touching its contents. Call before writing, so nothing sensitive is ever written
     * to a file others can read.
     */
    static void prepare(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        restrict(file);
    }

    /** Removes access for everyone but the owner. Idempotent. */
    static void restrict(Path file) {
        File f = file.toFile();
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setExecutable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }
}
