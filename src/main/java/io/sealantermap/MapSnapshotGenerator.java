package io.sealantermap;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MapSnapshotGenerator {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int CHUNKS_PER_REGION_EDGE = 32;
    private static final int CHUNKS_PER_REGION = CHUNKS_PER_REGION_EDGE * CHUNKS_PER_REGION_EDGE;
    private static final int LOCATION_TABLE_BYTES = CHUNKS_PER_REGION * 4;

    private MapSnapshotGenerator() {
    }

    static Inspection inspect(Path regionDir) throws IOException {
        ScanData scan = scanRegions(regionDir);
        return new Inspection(scan.widthChunks, scan.heightChunks, scan.discoveredChunks);
    }

    static Result generate(Path regionDir, Path outputPng, int chunkPixelSize, Color emptyColor, Color filledColor) throws IOException {
        ScanData scan = scanRegions(regionDir);
        if (scan.regions.isEmpty()) {
            Files.createDirectories(outputPng.getParent());
            BufferedImage empty = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            empty.setRGB(0, 0, emptyColor.getRGB());
            ImageIO.write(empty, "png", outputPng.toFile());
            return new Result(1, 1, 0, outputPng);
        }

        int safePixelSize = Math.max(1, chunkPixelSize);
        int widthPixels = scan.widthChunks * safePixelSize;
        int heightPixels = scan.heightChunks * safePixelSize;

        BufferedImage image = new BufferedImage(widthPixels, heightPixels, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(emptyColor);
        graphics.fillRect(0, 0, widthPixels, heightPixels);
        graphics.dispose();

        for (RegionPresence region : scan.regions) {
            for (int idx = region.chunks().nextSetBit(0); idx >= 0; idx = region.chunks().nextSetBit(idx + 1)) {
                int localX = idx & 31;
                int localZ = idx >> 5;
                int chunkX = (region.regionX() * CHUNKS_PER_REGION_EDGE) + localX;
                int chunkZ = (region.regionZ() * CHUNKS_PER_REGION_EDGE) + localZ;

                int pixelX = (chunkX - scan.minChunkX) * safePixelSize;
                int pixelZ = (chunkZ - scan.minChunkZ) * safePixelSize;

                fillBlock(image, pixelX, pixelZ, safePixelSize, filledColor.getRGB());
            }
        }

        Files.createDirectories(outputPng.getParent());
        ImageIO.write(image, "png", outputPng.toFile());

        return new Result(widthPixels, heightPixels, scan.discoveredChunks, outputPng);
    }

    private static ScanData scanRegions(Path regionDir) throws IOException {
        List<RegionPresence> regions = new ArrayList<>();
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        long discoveredChunks = 0L;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path regionFile : stream) {
                Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }

                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                BitSet occupiedChunks = readChunkPresence(regionFile);
                if (occupiedChunks.isEmpty()) {
                    continue;
                }

                for (int idx = occupiedChunks.nextSetBit(0); idx >= 0; idx = occupiedChunks.nextSetBit(idx + 1)) {
                    int localX = idx & 31;
                    int localZ = idx >> 5;
                    int chunkX = (regionX * CHUNKS_PER_REGION_EDGE) + localX;
                    int chunkZ = (regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
                    minChunkX = Math.min(minChunkX, chunkX);
                    maxChunkX = Math.max(maxChunkX, chunkX);
                    minChunkZ = Math.min(minChunkZ, chunkZ);
                    maxChunkZ = Math.max(maxChunkZ, chunkZ);
                    discoveredChunks++;
                }

                regions.add(new RegionPresence(regionX, regionZ, occupiedChunks));
            }
        }

        if (regions.isEmpty()) {
            return new ScanData(regions, 0, 0, 0L, 0, 0);
        }

        int widthChunks = maxChunkX - minChunkX + 1;
        int heightChunks = maxChunkZ - minChunkZ + 1;
        return new ScanData(regions, minChunkX, minChunkZ, discoveredChunks, widthChunks, heightChunks);
    }

    private static BitSet readChunkPresence(Path regionFile) throws IOException {
        BitSet occupied = new BitSet(CHUNKS_PER_REGION);
        byte[] table = new byte[LOCATION_TABLE_BYTES];

        try (InputStream input = Files.newInputStream(regionFile)) {
            int offset = 0;
            while (offset < LOCATION_TABLE_BYTES) {
                int read = input.read(table, offset, LOCATION_TABLE_BYTES - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            int base = i * 4;
            int locationOffset = ((table[base] & 0xFF) << 16)
                    | ((table[base + 1] & 0xFF) << 8)
                    | (table[base + 2] & 0xFF);
            int sectorCount = table[base + 3] & 0xFF;

            if (locationOffset != 0 && sectorCount != 0) {
                occupied.set(i);
            }
        }

        return occupied;
    }

    private static void fillBlock(BufferedImage image, int x, int z, int size, int rgb) {
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                image.setRGB(x + dx, z + dz, rgb);
            }
        }
    }

    record Result(int widthPixels, int heightPixels, long discoveredChunks, Path imagePath) {
    }

    record Inspection(int widthChunks, int heightChunks, long discoveredChunks) {
    }

    private record ScanData(
            List<RegionPresence> regions,
            int minChunkX,
            int minChunkZ,
            long discoveredChunks,
            int widthChunks,
            int heightChunks
    ) {
    }
}
