package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

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
    void holdsIsTrueOnlyForAFileThatIsExactlyOwnerReadWrite() throws IOException {
        assumeTrue(posix());
        Path file = dir.resolve("log");
        assertFalse(OwnerOnlyFile.holds(file), "a missing file");

        OwnerOnlyFile.prepare(file);
        assertTrue(OwnerOnlyFile.holds(file));

        for (String loose : new String[]{"rw-r-----", "rw----r--", "rw-rw----", "rwx------", "r--------", "-w-------"}) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(loose));
            assertFalse(OwnerOnlyFile.holds(file), loose);
        }
        OwnerOnlyFile.restrict(file);
        assertTrue(OwnerOnlyFile.holds(file));
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

    // ---- ACLs and failures ----

    private record Principal(String name) implements UserPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    /** An ACL view that remembers what was set, standing in for a Windows file system. */
    private static class FakeAcl implements AclFileAttributeView {
        final Principal owner = new Principal("owner");
        List<AclEntry> acl;

        FakeAcl(List<AclEntry> inherited) {
            acl = inherited;
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public List<AclEntry> getAcl() {
            return acl;
        }

        @Override
        public void setAcl(List<AclEntry> acl) throws IOException {
            this.acl = acl;
        }

        @Override
        public UserPrincipal getOwner() {
            return owner;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            throw new UnsupportedOperationException();
        }
    }

    private static AclEntry allow(UserPrincipal who) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(AclEntryPermission.READ_DATA).build();
    }

    @Test
    void whereTheFileSystemHasAclsOnlyTheOwnerGetsAccess() throws IOException {
        FakeAcl acl = new FakeAcl(List.of());
        acl.acl = List.of(allow(new Principal("Users")), allow(acl.owner));
        Path file = dir.resolve("secret.txt");
        assertFalse(OwnerOnlyFile.holds(null, acl), "another principal still has access");

        try (LogCapture logs = new LogCapture()) {
            OwnerOnlyFile.restrict(file, null, acl);

            assertEquals(List.of(), logs.lines());
        }

        assertEquals(1, acl.acl.size());
        AclEntry entry = acl.acl.get(0);
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertEquals(acl.owner, entry.principal());
        assertEquals(EnumSet.allOf(AclEntryPermission.class), entry.permissions());
        assertTrue(OwnerOnlyFile.holds(null, acl));
    }

    @Test
    void anAclViewThatCannotBeChangedIsOneWarningNamingTheFile() {
        FakeAcl acl = new FakeAcl(List.of()) {
            @Override
            public void setAcl(List<AclEntry> acl) throws IOException {
                throw new IOException("access denied");
            }
        };
        Path file = dir.resolve("refused.txt");

        try (LogCapture logs = new LogCapture()) {
            OwnerOnlyFile.restrict(file, null, acl);
            OwnerOnlyFile.restrict(file, null, acl);

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains(file.toString()), logs.lines().get(0));
            assertTrue(logs.lines().get(0).contains("access denied"), logs.lines().get(0));
        }
    }

    @Test
    void aFileSystemWithNeitherModesNorAclsIsOneWarningNamingTheFile() throws IOException {
        Path file = dir.resolve("unprotected.txt");

        try (LogCapture logs = new LogCapture()) {
            OwnerOnlyFile.restrict(file, null, null);
            OwnerOnlyFile.restrict(file, null, null);

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains(file.toString()), logs.lines().get(0));
            assertTrue(logs.lines().get(0).contains("neither POSIX modes nor ACLs"), logs.lines().get(0));
        }
        assertFalse(OwnerOnlyFile.holds(null, null));
    }

    @Test
    void aRestrictionThePosixFileSystemRefusesIsOneWarningNamingTheFile() {
        Path missing = dir.resolve("missing.txt");

        try (LogCapture logs = new LogCapture()) {
            OwnerOnlyFile.restrict(missing);
            OwnerOnlyFile.restrict(missing);

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains(missing.toString()), logs.lines().get(0));
        }
    }

    @Test
    void aFileThatWasRestrictedProducesNoWarning() throws IOException {
        Path file = dir.resolve("fine.txt");
        Files.writeString(file, "x");

        try (LogCapture logs = new LogCapture()) {
            OwnerOnlyFile.restrict(file);

            assertEquals(List.of(), logs.lines());
        }
    }
}
