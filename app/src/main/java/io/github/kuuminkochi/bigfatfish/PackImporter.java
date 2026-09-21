package io.github.kuuminkochi.bigfatfish;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates and installs artwork archives without exposing partially imported files. */
public final class PackImporter {
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DECOMPRESSED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final int MAX_ENTRIES = 128;

    private PackImporter() { }

    public static String importPack(Context context, Uri uri) throws IOException {
        if (context == null || uri == null) throw new IOException("missing artwork archive");
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("cannot open artwork archive");
        try (InputStream stream = input) {
            return importStream(context, stream);
        }
    }

    public static String importFile(Context context, File file) throws IOException {
        if (context == null || file == null || !file.isFile()) {
            throw new IOException("artwork archive not found");
        }
        try (InputStream input = new FileInputStream(file)) {
            return importStream(context, input);
        }
    }

    private static String importStream(Context context, InputStream input) throws IOException {
        File packs = new File(context.getFilesDir(), "packs");
        if (!packs.exists() && !packs.mkdirs()) throw new IOException("cannot create packs directory");
        if (!packs.isDirectory()) throw new IOException("packs path is not a directory");

        String token = UUID.randomUUID().toString();
        File archive = new File(packs, "." + token + ".zip");
        File staging = new File(packs, "." + token);
        if (!staging.mkdir()) throw new IOException("cannot create staging directory");
        boolean installed = false;
        try {
            String id = copyAndHash(input, archive);
            extract(archive, staging);
            SpritePack.loadDirectory(id, staging);

            File target = new File(packs, id);
            if (target.exists()) {
                if (!target.isDirectory()) throw new IOException("pack target is not a directory");
                SpritePack.loadDirectory(id, target);
            } else {
                moveDirectory(staging, target);
            }
            if (!SettingsStore.preferences(context).edit()
                    .putString(SettingsStore.KEY_PACK, id).commit()) {
                throw new IOException("cannot select imported pack");
            }
            installed = true;
            return id;
        } finally {
            if (!installed || staging.exists()) deleteTree(staging);
            if (archive.exists() && !archive.delete()) archive.deleteOnExit();
        }
    }

    private static String copyAndHash(InputStream input, File output) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[8192];
        long total = 0;
        try (OutputStream file = new BufferedOutputStream(new FileOutputStream(output))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (total > MAX_ARCHIVE_BYTES - count) throw new IOException("archive too large");
                file.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                total += count;
            }
        }
        return hex(digest.digest());
    }

    static void extract(File archive, File staging) throws IOException {
        Set<String> names = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++entries > MAX_ENTRIES) throw new IOException("too many archive entries");
                String name = entry.getName();
                validateZipName(name);
                if (!names.add(name)) throw new IOException("duplicate archive entry");
                if (entry.isDirectory()) continue;

                long declared = entry.getSize();
                long limit = "pack.json".equals(name) ? MAX_MANIFEST_BYTES : MAX_ENTRY_BYTES;
                if (declared > limit) throw new IOException("archive entry too large");
                if (declared >= 0 && total > MAX_DECOMPRESSED_BYTES - declared) {
                    throw new IOException("archive decompressed size too large");
                }
                File destination = safeChild(staging, name);
                File parent = destination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("cannot create staging path");
                }
                long written = 0;
                CRC32 crc = new CRC32();
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (written > limit - count || total > MAX_DECOMPRESSED_BYTES - count) {
                            throw new IOException("archive decompressed size too large");
                        }
                        output.write(buffer, 0, count);
                        crc.update(buffer, 0, count);
                        written += count;
                        total += count;
                    }
                }
                if (declared >= 0 && declared != written) throw new IOException("truncated archive entry");
                if (entry.getCrc() < 0 || crc.getValue() != entry.getCrc()) {
                    throw new IOException("archive checksum mismatch");
                }
            }
        }
        if (!names.contains("pack.json")) throw new IOException("missing pack manifest");
    }

    static void validateZipName(String name) throws IOException {
        if (name == null || name.length() == 0 || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0 || name.startsWith("/")
                || (name.length() >= 2 && name.charAt(1) == ':'
                && ((name.charAt(0) >= 'A' && name.charAt(0) <= 'Z')
                || (name.charAt(0) >= 'a' && name.charAt(0) <= 'z')))) {
            throw new IOException("unsafe archive path");
        }
        String checked = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (checked.length() == 0) throw new IOException("unsafe archive path");
        String[] parts = checked.split("/", -1);
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)) {
                throw new IOException("unsafe archive path");
            }
        }
    }

    private static File safeChild(File root, String name) throws IOException {
        File child = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(rootPath)) throw new IOException("unsafe archive path");
        return child;
    }

    private static void moveDirectory(File staging, File target) throws IOException {
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging.toPath(), target.toPath());
        }
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        if (!file.delete() && file.exists()) file.deleteOnExit();
    }
}
