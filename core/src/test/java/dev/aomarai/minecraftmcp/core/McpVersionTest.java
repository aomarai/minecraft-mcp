package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class McpVersionTest {

    private static InputStream properties(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void theVersionTheBuildWroteIsRead() {
        assertEquals("1.2.3", McpVersion.read(properties("version=1.2.3\n")));
        assertEquals("1.2.3-rc.1", McpVersion.read(properties("version = 1.2.3-rc.1 \n")));
    }

    @Test
    void withoutAFileAKeyOrAValueTheVersionIsDev() {
        assertEquals("dev", McpVersion.read(null));
        assertEquals("dev", McpVersion.read(properties("other=1\n")));
        assertEquals("dev", McpVersion.read(properties("version=\n")));
        assertEquals("dev", McpVersion.read(properties("version=   \n")));
    }

    @Test
    void anUnreadableFileIsTreatedAsNoVersion() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk");
            }
        };
        assertEquals("dev", McpVersion.read(broken));
    }

    @Test
    void theBuildWritesTheVersionIntoTheCoreJar() {
        assertNotEquals("dev", McpVersion.current());
        assertFalse(McpVersion.current().isBlank());
    }
}
