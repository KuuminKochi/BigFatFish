package io.github.kuuminkochi.bigfatfish;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PackIntegrityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private byte[] archive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pack.json"));
            zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test public void extractsAnIntactEntry() throws Exception {
        File zip = temporary.newFile("good.zip");
        Files.write(zip.toPath(), archive());
        File output = temporary.newFolder();
        PackImporter.extract(zip, output);
        assertTrue(java.util.Arrays.equals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(new File(output, "pack.json").toPath())));
    }

    @Test public void rejectsWrongChecksumEvenWhenLengthMatches() throws Exception {
        byte[] bytes = archive();
        boolean patched = false;
        for (int i = 0; i + 20 < bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 16] ^= 0x40;
                patched = true;
                break;
            }
        }
        assertTrue(patched);
        File zip = temporary.newFile("corrupt.zip");
        Files.write(zip.toPath(), bytes);
        File output = temporary.newFolder();
        assertThrows(IOException.class, () -> PackImporter.extract(zip, output));
    }
}
