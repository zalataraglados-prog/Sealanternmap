package io.sealantermap;

import org.bukkit.Material;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.logging.Logger;

final class TextureColorPalette {
    private TextureColorPalette() {
    }

    static Map<Material, Integer> load(Logger logger, String configuredJarPath) {
        Path jar = resolveMinecraftJar(configuredJarPath);
        if (jar == null) {
            logger.info("Sealantermap color palette: Minecraft client jar not found, using legacy color rules.");
            return Collections.emptyMap();
        }

        EnumMap<Material, Integer> palette = new EnumMap<>(Material.class);
        Map<String, Integer> textureColorCache = new HashMap<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Material material : Material.values()) {
                if (!material.isBlock() || material.name().endsWith("AIR")) {
                    continue;
                }

                Integer rgb = resolveMaterialColor(zip, material, textureColorCache);
                if (rgb != null) {
                    palette.put(material, rgb);
                }
            }
            logger.info("Sealantermap color palette loaded from " + jar + ", materials=" + palette.size());
        } catch (Exception e) {
            logger.warning("Failed to build texture palette from " + jar + ": " + e.getMessage());
            return Collections.emptyMap();
        }

        return palette;
    }

    private static Integer resolveMaterialColor(ZipFile zip, Material material, Map<String, Integer> textureColorCache) {
        List<String> candidates = textureCandidates(material);
        for (String name : candidates) {
            String blockTexture = "assets/minecraft/textures/block/" + name + ".png";
            Integer fromBlock = textureColorCache.computeIfAbsent(blockTexture, key -> readTextureColor(zip, key));
            if (fromBlock != null) {
                return fromBlock;
            }

            String itemTexture = "assets/minecraft/textures/item/" + name + ".png";
            Integer fromItem = textureColorCache.computeIfAbsent(itemTexture, key -> readTextureColor(zip, key));
            if (fromItem != null) {
                return fromItem;
            }
        }
        return null;
    }

    private static Integer readTextureColor(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            BufferedImage img = ImageIO.read(input);
            if (img == null) {
                return null;
            }
            return averageOpaqueColor(img);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Integer averageOpaqueColor(BufferedImage img) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        int width = img.getWidth();
        int height = img.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 16) {
                    continue;
                }
                r += (argb >>> 16) & 0xFF;
                g += (argb >>> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return new Color((int) (r / n), (int) (g / n), (int) (b / n)).getRGB();
    }

    private static List<String> textureCandidates(Material material) {
        String raw = material.name().toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add(raw);

        switch (raw) {
            case "grass_block" -> candidates.add("grass_block_top");
            case "podzol" -> candidates.add("podzol_top");
            case "mycelium" -> candidates.add("mycelium_top");
            case "snow_block" -> candidates.add("snow");
            case "short_grass", "tall_grass", "fern", "large_fern" -> candidates.add("grass");
            case "water", "bubble_column" -> candidates.add("water_still");
            case "lava" -> candidates.add("lava_still");
            default -> {
                // no-op
            }
        }

        String normalized = raw;
        normalized = stripSuffix(normalized, "_wall_hanging_sign");
        normalized = stripSuffix(normalized, "_hanging_sign");
        normalized = stripSuffix(normalized, "_wall_sign");
        normalized = stripSuffix(normalized, "_sign");
        normalized = stripSuffix(normalized, "_fence_gate");
        normalized = stripSuffix(normalized, "_pressure_plate");
        normalized = stripSuffix(normalized, "_trapdoor");
        normalized = stripSuffix(normalized, "_stairs");
        normalized = stripSuffix(normalized, "_button");
        normalized = stripSuffix(normalized, "_fence");
        normalized = stripSuffix(normalized, "_slab");
        normalized = stripSuffix(normalized, "_wall");
        normalized = stripSuffix(normalized, "_door");
        normalized = stripSuffix(normalized, "_carpet");
        normalized = stripSuffix(normalized, "_bed");
        normalized = stripSuffix(normalized, "_banner");
        if (!normalized.equals(raw)) {
            candidates.add(normalized);
        }

        if (raw.endsWith("_log")) {
            candidates.add(raw + "_top");
            candidates.add(raw.replace("_log", "_wood"));
        }
        if (raw.endsWith("_wood")) {
            candidates.add(raw + "_top");
            candidates.add(raw.replace("_wood", "_log"));
        }
        if (raw.startsWith("waxed_")) {
            candidates.add(raw.substring("waxed_".length()));
        }

        return deduplicate(candidates);
    }

    private static List<String> deduplicate(List<String> items) {
        List<String> out = new ArrayList<>(items.size());
        for (String item : items) {
            if (item == null || item.isBlank() || out.contains(item)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    private static String stripSuffix(String raw, String suffix) {
        if (raw.endsWith(suffix) && raw.length() > suffix.length()) {
            return raw.substring(0, raw.length() - suffix.length());
        }
        return raw;
    }

    private static Path resolveMinecraftJar(String configuredJarPath) {
        if (configuredJarPath != null && !configuredJarPath.isBlank()) {
            Path configured = Paths.get(configuredJarPath.trim());
            if (Files.isRegularFile(configured)) {
                return configured;
            }
        }

        List<Path> candidates = new ArrayList<>();
        collectVersionJars(candidates, Paths.get(System.getProperty("user.home"), ".minecraft", "versions"));

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            collectVersionJars(candidates, Paths.get(appData, ".minecraft", "versions"));
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingLong(TextureColorPalette::safeLastModified).reversed());
        return candidates.get(0);
    }

    private static void collectVersionJars(List<Path> out, Path versionsDir) {
        if (!Files.isDirectory(versionsDir)) {
            return;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versionsDir)) {
            for (Path versionDir : dirs) {
                if (!Files.isDirectory(versionDir)) {
                    continue;
                }
                String version = versionDir.getFileName().toString();
                Path versionJar = versionDir.resolve(version + ".jar");
                if (Files.isRegularFile(versionJar)) {
                    out.add(versionJar);
                } else {
                    try (DirectoryStream<Path> jarFiles = Files.newDirectoryStream(versionDir, "*.jar")) {
                        for (Path jar : jarFiles) {
                            if (Files.isRegularFile(jar)) {
                                out.add(jar);
                            }
                        }
                    } catch (IOException ignored) {
                        // no-op
                    }
                }
            }
        } catch (IOException ignored) {
            // no-op
        }
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
