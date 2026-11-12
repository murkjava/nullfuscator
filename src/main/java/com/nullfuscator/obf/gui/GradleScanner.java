package com.nullfuscator.obf.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public final class GradleScanner {

    private GradleScanner() {
    }

    public static List<File> scanCommonLibraries() {
        return scanGradleLibraries();
    }

    public static List<File> scanGradleLibraries() {
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

        List<String> keywords = Arrays.asList(
                "fabric-loader-", "sponge-mixin-", "fabric-api-",
                "paper-api-", "spigot-api-", "spigot-", "purpur-api-",
                "velocity-api-", "bungeecord-api-", "protocollib",
                "grimapi", "fastnbt", "adventure-api-",
                "gson-", "guava-", "brigadier-", "fastutil-"
        );
        Path modules2 = gradleCaches.resolve("modules-2");
        if (Files.isDirectory(modules2)) {
            result.addAll(scanByKeywords(modules2, 10, keywords));
        }

        return deduplicate(result);
    }

    public static List<File> scanMavenLibraries() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            return Collections.emptyList();
        }

        Path m2Repo = Paths.get(userHome, ".m2", "repository");
        if (!Files.isDirectory(m2Repo)) {
            return Collections.emptyList();
        }

        List<String> keywords = Arrays.asList(
                "paper-api-", "spigot-api-", "spigot-", "bukkit-",
                "purpur-api-", "velocity-api-", "bungeecord-api-",
                "protocollib", "grim", "fastnbt", "adventure-api-",
                "fabric-loader-", "fabric-api-", "sponge-mixin-",
                "gson-", "guava-", "brigadier-", "fastutil-"
        );
        return scanByKeywords(m2Repo, 10, keywords);
    }

    public static List<File> scanDirectory(File target) {
        if (target == null || !target.exists()) {
            return Collections.emptyList();
        }
        if (target.isFile()) {
            String name = target.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")) {
                return Collections.singletonList(target);
            }
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(target.toPath(), 8)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc")) {
                        result.add(p.toFile());
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return deduplicate(result);
    }

    private static List<File> scanByKeywords(Path root, int maxDepth, List<String> keywords) {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }

        Map<String, Path> latestByKeyword = new LinkedHashMap<>();
        Map<String, Long> timeByKeyword = new HashMap<>();

        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".jar") || name.contains("-sources") || name.contains("-javadoc")) {
                        return;
                    }
                    long mtime = -1L;
                    for (String kw : keywords) {
                        String kwLower = kw.toLowerCase(Locale.ROOT);
                        boolean match = kwLower.endsWith("-")
                                ? name.startsWith(kwLower)
                                : name.contains(kwLower);
                        if (match) {
                            if (mtime < 0) {
                                try {
                                    mtime = Files.getLastModifiedTime(p).toMillis();
                                } catch (Exception e) {
                                    mtime = 0L;
                                }
                            }
                            long currentBest = timeByKeyword.getOrDefault(kw, -1L);
                            if (mtime > currentBest) {
                                timeByKeyword.put(kw, mtime);
                                latestByKeyword.put(kw, p);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }

        List<File> result = new ArrayList<>();
        for (Path p : latestByKeyword.values()) {
            File f = p.toFile();
            if (!result.contains(f)) {
                result.add(f);
            }
        }
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

            latest.ifPresent(p -> {
                File file = p.toFile();
                if (!result.contains(file)) {
                    result.add(file);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static List<File> deduplicate(List<File> files) {
        List<File> unique = new ArrayList<>(files.size());
        for (File f : files) {
            if (!unique.contains(f)) {
                unique.add(f);
            }
        }
        return unique;
    }
}