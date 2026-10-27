package dev.aomarai.minecraftmcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files that hold secrets or sensitive records (the config with its token, the audit log) are readable and
 * writable by their owner only: mode {@code 0600} where the file system has POSIX modes, and an ACL that grants
 * the owner alone where it has ACLs (Windows). Where neither works the file keeps what its folder gave it, and one
 * warning per file says so, naming the file and never its contents.
 */
final class OwnerOnlyFile {

    private static final System.Logger LOG = System.getLogger(OwnerOnlyFile.class.getName());

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Files already warned about, so a log that is prepared on every entry warns once, not on every entry. */
    private static final Set<Path> WARNED = ConcurrentHashMap.newKeySet();

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
     * Whether {@code file} exists and is already owner-only, with one stat. Where the file system has neither POSIX
     * modes nor ACLs it cannot tell, and says no, so the caller falls back to {@link #prepare}.
     */
    static boolean holds(Path file) {
        try {
            return holds(Files.getFileAttributeView(file, PosixFileAttributeView.class),
                    Files.getFileAttributeView(file, AclFileAttributeView.class));
        } catch (IOException e) {
            return false;
        }
    }

    /** Owner-only by POSIX mode, or else by ACL: every entry is the owner's. */
    static boolean holds(PosixFileAttributeView posix, AclFileAttributeView acl) throws IOException {
        if (posix != null) {
            return posix.readAttributes().permissions().equals(OWNER_ONLY);
        }
        if (acl == null) {
            return false;
        }
        UserPrincipal owner = acl.getOwner();
        return acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner));
    }

    /** Removes access for everyone but the owner. Idempotent. Warns once per file when that is not possible. */
    static void restrict(Path file) {
        restrict(file, Files.getFileAttributeView(file, PosixFileAttributeView.class),
                Files.getFileAttributeView(file, AclFileAttributeView.class));
    }

    static void restrict(Path file, PosixFileAttributeView posix, AclFileAttributeView acl) {
        try {
            if (posix != null) {
                posix.setPermissions(OWNER_ONLY);
            } else if (acl != null) {
                AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                acl.setAcl(List.of(ownerOnly));
            } else {
                warn(file, "the file system has neither POSIX modes nor ACLs");
            }
        } catch (IOException | RuntimeException e) {
            warn(file, e.toString());
        }
    }

    private static void warn(Path file, String why) {
        if (WARNED.add(file.toAbsolutePath())) {
            LOG.log(System.Logger.Level.WARNING, "Could not restrict {0} to its owner, so others who can read its "
                    + "folder may be able to read it: {1}", file, why);
        }
    }
}
