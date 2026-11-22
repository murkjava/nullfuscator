package com.nullfuscator.obf.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class IntegrityGuard {

    private static final String PREFIX = "#:";
    private static volatile boolean initialized;
    private static volatile boolean failed;
    private static volatile long deadline;
    private static String[] entries;
    private static int cursor;

    private IntegrityGuard() {
    }

    public static void check() {
        if (failed) {
            throw new SecurityException();
        }
        if (initialized && System.nanoTime() - deadline < 0) {
            return;
        }

        synchronized (IntegrityGuard.class) {
            if (failed) {
                throw new SecurityException();
            }
            if (initialized && System.nanoTime() - deadline < 0) {
                return;
            }

            try {
                byte[] index = read("#INDEX#", 4 * 1024 * 1024);
                String expected = "#:0000000000000000000000000000000000000000000000000000000000000000";
                if (!hex(digest(index)).equals(expected.substring(PREFIX.length()))) {
                    throw new SecurityException();
                }

                if (!initialized) {
                    entries = new String(index, StandardCharsets.UTF_8).split("\n");
                    for (String entry : entries) {
                        if (entry.isEmpty() || entry.isBlank()) {
                            continue;
                        }
                        verify(entry);
                    }
                } else {
                    verify(entries[cursor++ % entries.length]);
                    if (cursor == entries.length) {
                        cursor = 0;
                    }
                }

                deadline = System.nanoTime() + 5_000_000_000L;
                initialized = true;
            } catch (Exception e) {
                failed = true;
                throw new SecurityException();
            }
        }
    }

    private static void verify(String entry) throws Exception {
        int separator = entry.indexOf(' ');
        if (separator != 64) {
            throw new SecurityException();
        }
        String path = entry.substring(65);
        byte[] bytes = read(path, 32 * 1024 * 1024);
        normalize(bytes);
        if (!hex(digest(bytes)).equals(entry.substring(0, 64))) {
            throw new SecurityException();
        }
    }

    private static byte[] read(String path, int limit) throws Exception {
        try (InputStream in = IntegrityGuard.class.getResourceAsStream("/".concat(path))) {
            if (in == null) {
                throw new SecurityException();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() > limit - n) {
                    throw new SecurityException();
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static void normalize(byte[] bytes) {
        byte[] prefix = PREFIX.getBytes(StandardCharsets.US_ASCII);
        int markerLen = prefix.length + 64;
        for (int i = 0; i <= bytes.length - markerLen; i++) {
            boolean match = true;
            for (int j = 0; j < prefix.length; j++) {
                if (bytes[i + j] != prefix[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }

            if (i < 3 || bytes[i - 3] != 1 || bytes[i - 2] != 0 || (bytes[i - 1] & 0xFF) != markerLen) {
                continue;
            }
            for (int j = 0; j < 64; j++) {
                bytes[i + prefix.length + j] = '0';
            }
        }
    }

    private static byte[] digest(byte[] data) throws Exception {
        byte[] a = { (byte) (83 ^ 0x33), (byte) (72 ^ 0x33), (byte) (65 ^ 0x33), (byte) (45 ^ 0x33), (byte) (50 ^ 0x33), (byte) (53 ^ 0x33), (byte) (54 ^ 0x33) };
        for (int i = 0; i < a.length; i++) {
            a[i] ^= (byte) 0x33;
        }
        return MessageDigest.getInstance(new String(a, StandardCharsets.US_ASCII)).digest(data);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            int h = v >>> 4;
            int l = v & 0x0F;
            out[i * 2] = (char) (h < 10 ? '0' + h : 'a' + (h - 10));
            out[i * 2 + 1] = (char) (l < 10 ? '0' + l : 'a' + (l - 10));
        }
        return new String(out);
    }
}
