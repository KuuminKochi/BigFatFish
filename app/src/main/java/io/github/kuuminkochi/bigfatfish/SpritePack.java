package io.github.kuuminkochi.bigfatfish;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/** Immutable, decoded artwork pack. All decoding happens during load. */
public final class SpritePack {
    public static final int ACTIVE = 0;
    public static final int SLEEP = 1;
    public static final int REACT = 2;

    static final int MAX_FRAME_REFERENCES = 120;
    static final long MAX_DECODED_BYTES = 16L * 1024L * 1024L;
    private static final int MIN_DIMENSION = 8;
    private static final int MAX_DIMENSION = 512;

    public final String id;
    public final String name;
    public final String author;
    public final String license;
    public final int width;
    public final int height;
    public final float attachmentX;
    public final float attachmentY;

    private final Frame[] active;
    private final Frame[] sleep;
    private final Frame[] react;
    private final long activeDuration;
    private final long sleepDuration;
    private final long reactDuration;

    private SpritePack(String id, String name, String author, String license,
                       int width, int height, float attachmentX, float attachmentY,
                       Frame[] active, Frame[] sleep, Frame[] react) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.license = license;
        this.width = width;
        this.height = height;
        this.attachmentX = attachmentX;
        this.attachmentY = attachmentY;
        this.active = active;
        this.sleep = sleep;
        this.react = react;
        activeDuration = duration(active);
        sleepDuration = duration(sleep);
        reactDuration = duration(react);
    }

    public static SpritePack load(Context context, String id) throws IOException {
        if (context == null || id == null || id.length() == 0) {
            throw new IOException("missing pack");
        }
        if ("builtin".equals(id)) {
            return loadSource(id, new AssetSource(context.getAssets(), "builtin"));
        }
        if (!isImportedId(id)) {
            throw new IOException("invalid pack id");
        }
        File root = new File(new File(context.getFilesDir(), "packs"), id);
        if (!root.isDirectory()) {
            throw new IOException("pack not found");
        }
        return loadSource(id, new FileSource(root));
    }

    /** Returns manifest metadata without decoding any frame. */
    public static String displayName(Context context, String id) {
        if (context == null || id == null || id.length() == 0) {
            throw new IllegalArgumentException("missing pack");
        }
        try {
            Source source;
            if ("builtin".equals(id)) {
                source = new AssetSource(context.getAssets(), "builtin");
            } else {
                if (!isImportedId(id)) {
                    throw new IOException("invalid pack id");
                }
                source = new FileSource(new File(new File(context.getFilesDir(), "packs"), id));
            }
            JSONObject manifest;
            try (InputStream input = source.open("pack.json")) {
                manifest = new JSONObject(readUtf8(input));
            }
            return requiredText(manifest, "name");
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("invalid sprite pack", e);
        }
    }

    public Bitmap frameFor(int mode, long elapsedMs, float speed) {
        Frame[] frames = frames(mode);
        long total = durationMs(mode);
        if (total <= 0) {
            throw new IllegalStateException("empty sprite pack state");
        }
        long elapsed = elapsedMs < 0 ? 0 : elapsedMs;
        float rate = Float.isFinite(speed) && speed > 0f ? speed : 1f;
        long scaled;
        if (rate == 1f) {
            scaled = elapsed % total;
        } else {
            double value = elapsed * (double) rate;
            scaled = (long) (value % total);
        }
        long cursor = 0;
        for (Frame frame : frames) {
            cursor += frame.durationMs;
            if (scaled < cursor) {
                return frame.bitmap;
            }
        }
        return frames[frames.length - 1].bitmap;
    }

    public long durationMs(int mode) {
        switch (mode) {
            case ACTIVE: return activeDuration;
            case SLEEP: return sleepDuration;
            case REACT: return reactDuration;
            default: throw new IllegalArgumentException("unknown sprite state");
        }
    }
    private Frame[] frames(int mode) {
        switch (mode) {
            case ACTIVE: return active;
            case SLEEP: return sleep;
            case REACT: return react;
            default: throw new IllegalArgumentException("unknown sprite state");
        }
    }

    static SpritePack loadDirectory(String id, File root) throws IOException {
        return loadSource(id, new FileSource(root));
    }

    static boolean isImportedId(String id) {
        if (id.length() != 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static SpritePack loadSource(String id, Source source) throws IOException {
        JSONObject manifest;
        try (InputStream input = source.open("pack.json")) {
            manifest = new JSONObject(readUtf8(input));
        } catch (JSONException e) {
            throw new IOException("malformed pack manifest", e);
        }
        try {
            if (manifest.optInt("version", -1) != 1) {
                throw new IOException("unsupported pack version");
            }
            String name = requiredText(manifest, "name");
            String author = requiredText(manifest, "author");
            String license = requiredText(manifest, "license");
            JSONArray attachment = manifest.getJSONArray("attachment");
            if (attachment.length() != 2) throw new IOException("bad attachment");
            float attachmentX = finiteNormalized((float) attachment.getDouble(0), "attachment x");
            float attachmentY = finiteNormalized((float) attachment.getDouble(1), "attachment y");
            JSONObject states = manifest.getJSONObject("states");
            JSONArray activeJson = states.getJSONArray("active");
            JSONArray sleepJson = states.getJSONArray("sleep");
            JSONArray reactJson = states.has("react") ? states.getJSONArray("react") : null;
            if (activeJson.length() == 0 || sleepJson.length() == 0) {
                throw new IOException("active and sleep are required");
            }
            if (reactJson != null && reactJson.length() == 0) {
                throw new IOException("react must contain frames when provided");
            }
            int references = activeJson.length() + sleepJson.length()
                    + (reactJson == null ? 0 : reactJson.length());
            if (references > MAX_FRAME_REFERENCES) throw new IOException("too many frames");

            DecodeState decode = new DecodeState(source);
            Frame[] active = readFrames(activeJson, decode);
            Frame[] sleep = readFrames(sleepJson, decode);
            Frame[] react = reactJson == null ? active : readFrames(reactJson, decode);
            if (decode.width < MIN_DIMENSION || decode.height < MIN_DIMENSION
                    || decode.width > MAX_DIMENSION || decode.height > MAX_DIMENSION
                    || decode.width > decode.height * 4L || decode.height > decode.width * 4L) {
                throw new IOException("invalid image dimensions");
            }
            return new SpritePack(id, name, author, license, decode.width, decode.height,
                    attachmentX, attachmentY, active, sleep, react);
        } catch (JSONException | ClassCastException | IllegalArgumentException e) {
            throw new IOException("malformed pack manifest", e);
        }
    }

    private static Frame[] readFrames(JSONArray array, DecodeState decode) throws JSONException, IOException {
        Frame[] frames = new Frame[array.length()];
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            String path = object.getString("file");
            if (!isSafeFramePath(path)) throw new IOException("invalid frame path");
            long duration = object.getLong("durationMs");
            if (duration < 16 || duration > 2000) throw new IOException("invalid frame duration");
            Bitmap bitmap = decode.bitmap(path);
            frames[i] = new Frame(bitmap, duration);
        }
        return frames;
    }

    private static boolean isSafeFramePath(String path) {
        return path.length() > 4 && path.endsWith(".png") && path.indexOf('\\') < 0
                && !path.startsWith("/") && !path.startsWith(".")
                && path.indexOf("//") < 0 && path.indexOf("..") < 0;
    }

    private static float finiteNormalized(float value, String label) throws IOException {
        if (!Float.isFinite(value) || value < 0f || value > 1f) throw new IOException("bad " + label);
        return value;
    }

    private static String requiredText(JSONObject object, String key) throws IOException {
        String value = object.optString(key, "");
        if (value.length() == 0 || value.length() > 256) throw new IOException("missing " + key);
        return value;
    }

    private static long duration(Frame[] frames) {
        long total = 0;
        for (Frame frame : frames) total += frame.durationMs;
        return total;
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() > 65536 - count) throw new IOException("manifest too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }

    private static final class Frame {
        final Bitmap bitmap;
        final long durationMs;
        Frame(Bitmap bitmap, long durationMs) { this.bitmap = bitmap; this.durationMs = durationMs; }
    }

    private static final class DecodeState {
        final Source source;
        final HashMap<String, Bitmap> cache = new HashMap<>();
        int width;
        int height;
        long decodedBytes;
        DecodeState(Source source) { this.source = source; }

        Bitmap bitmap(String path) throws IOException {
            Bitmap cached = cache.get(path);
            if (cached != null) return cached;
            if (!isPng(path)) throw new IOException("frame is not PNG");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = source.open(path)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth < MIN_DIMENSION || bounds.outHeight < MIN_DIMENSION
                    || bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION
                    || bounds.outWidth > bounds.outHeight * 4L || bounds.outHeight > bounds.outWidth * 4L) {
                throw new IOException("invalid image dimensions");
            }
            if (width == 0) {
                width = bounds.outWidth;
                height = bounds.outHeight;
            } else if (width != bounds.outWidth || height != bounds.outHeight) {
                throw new IOException("inconsistent image dimensions");
            }
            long bytes = bounds.outWidth * (long) bounds.outHeight * 4L;
            if (decodedBytes > MAX_DECODED_BYTES - bytes) throw new IOException("decoded artwork too large");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap bitmap;
            try (InputStream input = source.open(path)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                throw new IOException("invalid PNG image");
            }
            long actualBytes = bitmap.getRowBytes() * (long) bitmap.getHeight();
            if (decodedBytes > MAX_DECODED_BYTES - actualBytes) {
                throw new IOException("decoded artwork too large");
            }
            decodedBytes += actualBytes;
            cache.put(path, bitmap);
            return bitmap;
        }

        private boolean isPng(String path) throws IOException {
            try (InputStream input = source.open(path)) {
                byte[] signature = new byte[8];
                int offset = 0;
                while (offset < signature.length) {
                    int count = input.read(signature, offset, signature.length - offset);
                    if (count < 0) return false;
                    offset += count;
                }
                return signature[0] == (byte) 0x89 && signature[1] == 0x50
                        && signature[2] == 0x4e && signature[3] == 0x47
                        && signature[4] == 0x0d && signature[5] == 0x0a
                        && signature[6] == 0x1a && signature[7] == 0x0a;
            }
        }
    }
    private interface Source {
        InputStream open(String path) throws IOException;
    }

    private static final class AssetSource implements Source {
        final AssetManager assets;
        final String root;
        AssetSource(AssetManager assets, String root) { this.assets = assets; this.root = root; }
        @Override public InputStream open(String path) throws IOException {
            return assets.open(root + "/" + path, AssetManager.ACCESS_STREAMING);
        }
    }

    private static final class FileSource implements Source {
        final File root;
        FileSource(File root) { this.root = root; }
        @Override public InputStream open(String path) throws IOException {
            File file = new File(root, path);
            String rootPath = root.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(rootPath)) throw new IOException("path escape");
            return new FileInputStream(file);
        }
    }
}
