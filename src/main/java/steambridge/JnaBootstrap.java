/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Prepares JNA for use under Minecraft's LaunchClassLoader (Forge 1.7.10).
 * <p>
 * Without this, {@code Native.&lt;clinit&gt;} fails with
 * {@code UnsatisfiedLinkError: Can't obtain class com.sun.jna.CallbackReference}.
 * SteamAPI.init() can still succeed (Spacewar binds); the failure hits later when the first
 * JNA {@code Structure} is built in {@code SteamOffsets.validateLayout()}.
 */
public final class JnaBootstrap {

    private static volatile boolean done;

    private JnaBootstrap() {}

    /** Idempotent. Call from client preInit before any Steam/JNA code runs. */
    public static void prepare() {
        if (done) {
            return;
        }
        synchronized (JnaBootstrap.class) {
            if (done) {
                return;
            }
            try {
                // Prefer jnidispatch we unpack; ignore any broken system JNA.
                System.setProperty("jna.nosys", "true");

                // Do not let FML/LaunchWrapper ASM-transform JNA (breaks JNI name lookups).
                excludeFromLaunchTransformers("com.sun.jna.");

                // Unpack jnidispatch next to a stable path JNA can find before Native.<clinit>.
                File nativeDir = unpackJnidispatch();
                if (nativeDir != null) {
                    System.setProperty("jna.boot.library.path", nativeDir.getAbsolutePath());
                    SteamBridgeMod.LOG.info("[JNA] jna.boot.library.path={}", nativeDir.getAbsolutePath());
                }

                ClassLoader cl = JnaBootstrap.class.getClassLoader();
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(cl);
                    // Load CallbackReference first so JNI FindClass can resolve it during Native.initIDs.
                    Class.forName("com.sun.jna.CallbackReference", true, cl);
                    Class.forName("com.sun.jna.Native", true, cl);
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
                SteamBridgeMod.LOG.info("[JNA] Bootstrap OK.");
            } catch (Throwable t) {
                SteamBridgeMod.LOG.error("[JNA] Bootstrap failed: {}", t.toString());
                SteamBridgeMod.LOG.error("[JNA] Bootstrap stack", t);
            } finally {
                done = true;
            }
        }
    }

    private static void excludeFromLaunchTransformers(String prefix) {
        try {
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch");
            Object classLoader = launchClass.getField("classLoader").get(null);
            if (classLoader == null) {
                return;
            }
            Method add = classLoader.getClass().getMethod("addTransformerExclusion", String.class);
            add.invoke(classLoader, prefix);
            SteamBridgeMod.LOG.info("[JNA] addTransformerExclusion(\"{}\")", prefix);
        } catch (ClassNotFoundException ignored) {
            // Not under LaunchWrapper.
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[JNA] transformer exclusion failed: {}", t.toString());
        }
    }

    /**
     * Extracts platform jnidispatch from the shaded jar into a temp dir.
     * Returns the directory that contains the native library file (not the file itself).
     */
    private static File unpackJnidispatch() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String resource;
        String fileName;
        if (os.contains("win")) {
            boolean x64 = arch.contains("64") && !arch.contains("aarch");
            boolean arm = arch.contains("aarch") || arch.contains("arm64");
            if (arm) {
                resource = "/com/sun/jna/win32-aarch64/jnidispatch.dll";
            } else if (x64) {
                resource = "/com/sun/jna/win32-x86-64/jnidispatch.dll";
            } else {
                resource = "/com/sun/jna/win32-x86/jnidispatch.dll";
            }
            fileName = "jnidispatch.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            resource = arch.contains("aarch") || arch.contains("arm")
                ? "/com/sun/jna/darwin-aarch64/libjnidispatch.jnilib"
                : "/com/sun/jna/darwin-x86-64/libjnidispatch.jnilib";
            fileName = "libjnidispatch.jnilib";
        } else {
            // Linux - best-effort x86_64
            resource = "/com/sun/jna/linux-x86-64/libjnidispatch.so";
            fileName = "libjnidispatch.so";
        }

        try (InputStream in = JnaBootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                SteamBridgeMod.LOG.error("[JNA] Resource not in jar: {}", resource);
                return null;
            }
            File dir = Files.createTempDirectory("steambridge_jna").toFile();
            dir.deleteOnExit();
            File out = new File(dir, fileName);
            out.deleteOnExit();
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            SteamBridgeMod.LOG.info("[JNA] Unpacked {} ({} bytes)", out.getAbsolutePath(), out.length());
            return dir;
        } catch (Throwable t) {
            SteamBridgeMod.LOG.error("[JNA] Failed to unpack jnidispatch: {}", t.toString());
            return null;
        }
    }
}
