// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Only installed APK assets and installed native libraries; deliberately has no network API. */
public final class EngineAssets {
    private static final int MAX_FILES = 10000, MAX_INDEX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private EngineAssets() {}

    public static final class Availability {
        public final boolean installed;
        public final String version, support, detail;
        Availability(boolean installed, String version, String support, String detail) {
            this.installed = installed; this.version = version; this.support = support; this.detail = detail;
        }
    }

    /** Re-resolve PackageManager metadata after a signed split install; never trust a preference flag. */
    static Context installedContext(Context context) throws Exception {
        return context.createPackageContext(context.getPackageName(), 0);
    }
    public static Availability availability(Context context, Engine engine) {
        try {
            Context installed = installedContext(context);
            Properties info = properties(installed.getAssets(), "runtime-engine/" + engine.id + "/engine.properties");
            if (!"1".equals(info.getProperty("schema")) || !engine.id.equals(info.getProperty("engine")))
                throw new IOException("Unsupported engine descriptor");
            File lib = nativeDirectory(installed);
            String[] names = engine == Engine.NODE
                    ? new String[] {"libengine_host.so", "libengine_node.so", "libnode.so"}
                    : new String[] {"libengine_host.so", "libengine_python.so", "libpython3.14.so"};
            for (String name : names) {
                File file = new File(lib, name);
                if (!file.isFile() || file.length() < 4096) throw new IOException("Installed native library missing: " + name);
            }
            return new Availability(true, info.getProperty("version", "unknown"), info.getProperty("support", "unqualified"),
                    "Installed signed payload; device qualification is separate");
        } catch (Exception unavailable) {
            return new Availability(false, "", "", unavailable.getMessage() == null ? "Engine not installed" : unavailable.getMessage());
        }
    }
    static File nativeDirectory(Context context) throws IOException {
        ApplicationInfo info = context.getApplicationInfo();
        if (info.nativeLibraryDir == null) throw new IOException("Native extraction must be enabled by the app");
        File root = new File(info.nativeLibraryDir).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Installed native library directory is absent");
        return root;
    }

    static final class Prepared {
        final File home, app, work, entry, nativeLibs;
        Prepared(File home, File app, File work, File entry, File nativeLibs) {
            this.home = home; this.app = app; this.work = work; this.entry = entry; this.nativeLibs = nativeLibs;
        }
    }
    static Prepared prepare(Context context, EngineRequest request) throws Exception {
        Context installed = installedContext(context);
        Availability available = availability(installed, request.engine);
        if (!available.installed) throw new IOException(available.detail);
        File base = new File(context.getNoBackupFilesDir(), "runtime-engine-v1");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create private runtime directory");
        File home = extract(installed.getAssets(), "runtime-engine/" + request.engine.id, base, request.engine.id);
        File app = extract(installed.getAssets(), "runtime-engine/app", base, "app");
        File entry = Paths.inside(app, request.entry);
        if (!entry.isFile()) throw new IOException("Bundled entry missing: " + request.entry);
        File work = new File(base, "data");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create application data directory");
        return new Prepared(home, app, work, entry, nativeDirectory(installed));
    }

    private static final class Item {
        final String path, sha; final long size;
        Item(String path, String sha, long size) { this.path = path; this.sha = sha; this.size = size; }
    }
    private static File extract(AssetManager manager, String assetRoot, File base, String name) throws Exception {
        byte[] index;
        try (InputStream in = manager.open(assetRoot + "/files.sha256")) { index = read(in, MAX_INDEX_BYTES); }
        String identity = hex(hash().digest(index));
        File target = new File(base, name + "-" + identity);
        List<Item> files = parseIndex(index);
        // One-shot process has one extraction worker; immutable destinations are revalidated on every run.
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath()) && validExisting(target, files)) return target;
        if (target.exists()) delete(target);
        File pending = new File(base, "." + name + "-" + UUID.randomUUID());
        if (!pending.mkdir()) throw new IOException("Cannot stage app assets");
        try {
            for (Item item : files) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Asset preparation cancelled");
                File file = Paths.inside(pending, item.path);
                File parent = file.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create asset parent");
                MessageDigest sha = hash();
                long copied = 0;
                try (InputStream in = manager.open(assetRoot + "/" + item.path); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        copied += count;
                        if (copied > item.size) throw new IOException("Asset size mismatch");
                        sha.update(buffer, 0, count); out.write(buffer, 0, count);
                    }
                    out.getFD().sync();
                }
                if (copied != item.size || !hex(sha.digest()).equals(item.sha)) throw new IOException("Asset hash mismatch: " + item.path);
            }
            if (!pending.renameTo(target)) throw new IOException("Cannot atomically publish app assets");
        } finally { if (pending.exists()) delete(pending); }
        // Remove obsolete versions, but keep the app's writable data directory.
        File[] old = base.listFiles();
        if (old != null) for (File child : old) {
            if (!child.equals(target) && child.getName().startsWith(name + "-")) delete(child);
        }
        return target;
    }
    private static List<Item> parseIndex(byte[] data) throws IOException {
        List<Item> result = new ArrayList<Item>(); Set<String> seen = new HashSet<String>(); long total = 0;
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (line.length() == 0) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !fields[0].matches("[a-f0-9]{64}")) throw new IOException("Malformed asset index");
            long size;
            try { size = Long.parseLong(fields[1]); Paths.relative(fields[2]); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid indexed path/size", invalid); }
            if (size < 0 || size > MAX_BYTES || !seen.add(fields[2])) throw new IOException("Invalid indexed asset");
            String lower = fields[2].toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".so") || lower.endsWith(".node") || lower.endsWith(".dex") || lower.endsWith(".apk")
                    || lower.endsWith(".jar") || lower.endsWith(".pyc") || lower.endsWith(".pyd"))
                throw new IOException("Executable/native assets are not permitted");
            total += size;
            if (total > MAX_BYTES || result.size() >= MAX_FILES) throw new IOException("Asset budget exceeded");
            result.add(new Item(fields[2], fields[0], size));
        }
        if (result.isEmpty()) throw new IOException("Empty asset index");
        return result;
    }
    private static boolean validExisting(File root, List<Item> items) throws Exception {
        Set<String> expected = new HashSet<String>();
        for (Item item : items) {
            expected.add(item.path);
            File file = Paths.inside(root, item.path);
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() != item.size) return false;
            MessageDigest sha = hash();
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
            }
            if (!hex(sha.digest()).equals(item.sha)) return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            java.util.Iterator<java.nio.file.Path> paths = walk.iterator();
            int entries = 0;
            while (paths.hasNext()) {
                java.nio.file.Path path = paths.next();
                if (++entries > MAX_FILES * 8 || Files.isSymbolicLink(path)) return false;
                if (Files.isRegularFile(path) && !expected.contains(root.toPath().relativize(path).toString().replace(File.separatorChar, '/'))) return false;
            }
        }
        return true;
    }
    private static void delete(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot enumerate stale assets");
            for (File child : children) delete(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Cannot remove stale asset");
    }
    private static Properties properties(AssetManager assets, String path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = assets.open(path)) {
            byte[] bytes = read(in, 16384);
            props.load(new java.io.ByteArrayInputStream(bytes));
        }
        return props;
    }
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > max) throw new IOException("Asset metadata too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static MessageDigest hash() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
