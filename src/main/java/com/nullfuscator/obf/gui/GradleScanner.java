package com.nullfuscator.obf.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GradleScanner {

    private GradleScanner() {
    }

    public static List<File> scanCommonLibraries() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            return Collections.emptyList();
        }

        Path gradleCaches = Paths.get(userHome, ".gradle", "caches");
        if (!Files.isDirectory(gradleCaches)) {
            return Collections.emptyList();
        }

        List<File> result = new ArrayList<>();
        addLatestMatch(gradleCaches.resolve("fabric-loom"), "minecraft-merged-intermediary", result);
        addLatestMatch(gradleCaches.resolve("modules-2"), "fabric-loader-", result);
        addLatestMatch(gradleCaches.resolve("modules-2"), "sponge-mixin-", result);
        addLatestMatch(gradleCaches.resolve("modules-2"), "fabric-api-", result);
        addLatestMatch(gradleCaches.resolve("modules-2"), "gson-", result);
        addLatestMatch(gradleCaches.resolve("modules-2"), "brigadier-", result);

        return result;
    }

    private static void addLatestMatch(Path root, String keyword, List<File> result) {
        if (!Files.isDirectory(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root, 10)) {
            Optional<Path> latest = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        boolean match = keyword.endsWith("-")
                                ? name.startsWith(keyword.toLowerCase(Locale.ROOT))
                                : name.contains(keyword.toLowerCase(Locale.ROOT));
                        return name.endsWith(".jar")
                                && match
                                && !name.contains("-sources")
                                && !name.contains("-javadoc");
                    })
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }));

            latest.ifPresent(p -> result.add(p.toFile()));
        } catch (Exception ignored) {
        }
    }
}
