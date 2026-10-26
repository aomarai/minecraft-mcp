package dev.aomarai.minecraftmcp.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Files that hold secrets or sensitive records (the config with its token, the audit log) are
 * readable and writable by their owner only: mode {@code 0600} on POSIX, best effort where the
 * file system has no such modes.
 */
final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

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

    /**
     * Whether {@code file} exists and is already owner-only, with one stat. Where the file system has no POSIX
     * modes it cannot tell, and says no, so the caller falls back to {@link #prepare}.
     */
    static boolean holds(Path file) {
        try {
            return Files.getPosixFilePermissions(file).equals(OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
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
