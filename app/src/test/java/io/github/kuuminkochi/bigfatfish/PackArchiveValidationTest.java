package io.github.kuuminkochi.bigfatfish;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public final class PackArchiveValidationTest {
    @Test
    public void acceptsNestedForwardSlashFramePath() throws IOException {
        PackImporter.validateZipName("active/00.png");
    }

    @Test(expected = IOException.class)
    public void rejectsParentTraversal() throws IOException {
        PackImporter.validateZipName("active/../outside.png");
    }

    @Test(expected = IOException.class)
    public void rejectsAbsoluteAndBackslashPaths() throws IOException {
        PackImporter.validateZipName("/tmp\\outside.png");
    }

    @Test(expected = IOException.class)
    public void rejectsWindowsDrivePaths() throws IOException {
        PackImporter.validateZipName("C:/outside.png");
    }

    @Test
    public void importedIdsMustBeLowercaseSha256Hex() {
        assertTrue(SpritePack.isImportedId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(SpritePack.isImportedId("0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(SpritePack.isImportedId("builtin"));
    }
}
