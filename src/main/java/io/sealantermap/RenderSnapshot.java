package io.sealantermap;

final class RenderSnapshot {
    static final RenderSnapshot EMPTY = new RenderSnapshot(
            "idle",
            "startup",
            "waiting for first render",
            0L,
            0,
            0,
            0L,
            0L,
            0L,
            0L
    );

    final String status;
    final String reason;
    final String message;
    final long generatedEpochMs;
    final int width;
    final int height;
    final long chunkCount;
    final long regionFileCount;
    final long playerDataFileCount;
    final long renderCount;

    RenderSnapshot(
            String status,
            String reason,
            String message,
            long generatedEpochMs,
            int width,
            int height,
            long chunkCount,
            long regionFileCount,
            long playerDataFileCount,
            long renderCount
    ) {
        this.status = status;
        this.reason = reason;
        this.message = message;
        this.generatedEpochMs = generatedEpochMs;
        this.width = width;
        this.height = height;
        this.chunkCount = chunkCount;
        this.regionFileCount = regionFileCount;
        this.playerDataFileCount = playerDataFileCount;
        this.renderCount = renderCount;
    }
}
