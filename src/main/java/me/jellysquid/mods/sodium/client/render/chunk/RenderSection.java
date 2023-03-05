package me.jellysquid.mods.sodium.client.render.chunk;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.texture.SpriteUtil;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.client.render.chunk.ChunkOcclusionData;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;

import java.util.concurrent.CompletableFuture;

/**
 * The render state object for a chunk section. This contains all the graphics state for each render pass along with
 * data about the render in the chunk visibility graph.
 */
public class RenderSection {
    private static final long DEFAULT_VISIBILITY_DATA = calculateVisibilityData(ChunkRenderData.EMPTY.getOcclusionData());

    private final int chunkX, chunkY, chunkZ;
    @Deprecated(forRemoval = true) // reason: will be stored in an indexed array
    private long visibilityData;
    @Deprecated(forRemoval = true) // reason: pointer chains are inefficient, we want to move towards using indexed arrays
    private RenderSection adjDown, adjUp, adjNorth, adjSouth, adjWest, adjEast;
    @Deprecated(forRemoval = true) // reason: should use a bit array instead to keep this object independent of the graph state
    private int lastVisibleFrame = -1;
    private int flags;
    private ChunkUpdateType pendingUpdate;

    @Deprecated(forRemoval = true) // reason: render sections should not have references to their owner
    private final SodiumWorldRenderer worldRenderer;

    private final int chunkId;
    private final long regionId;

    private ChunkRenderData data = ChunkRenderData.ABSENT;
    private CompletableFuture<?> rebuildTask = null;

    private boolean disposed;

    private int lastAcceptedBuildTime = -1;

    public RenderSection(SodiumWorldRenderer worldRenderer, int chunkX, int chunkY, int chunkZ) {
        this.worldRenderer = worldRenderer;

        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;

        this.visibilityData = DEFAULT_VISIBILITY_DATA;

        int rX = this.getChunkX() & (RenderRegion.REGION_WIDTH - 1);
        int rY = this.getChunkY() & (RenderRegion.REGION_HEIGHT - 1);
        int rZ = this.getChunkZ() & (RenderRegion.REGION_LENGTH - 1);

        this.chunkId = RenderRegion.getChunkIndex(rX, rY, rZ);
        this.regionId = RenderRegion.getRegionKeyForChunk(this.chunkX, this.chunkY, this.chunkZ);
    }

    @Deprecated // reason: this object will no longer store adjacent nodes in the future
    public RenderSection getAdjacent(int dir) {
        return switch (dir) {
            case GraphDirection.DOWN -> this.adjDown;
            case GraphDirection.UP -> this.adjUp;
            case GraphDirection.NORTH -> this.adjNorth;
            case GraphDirection.SOUTH -> this.adjSouth;
            case GraphDirection.WEST -> this.adjWest;
            case GraphDirection.EAST -> this.adjEast;
            default -> null;
        };
    }

    @Deprecated // reason: this object will no longer store adjacent nodes in the future
    public void setAdjacentNode(int dir, RenderSection node) {
        switch (dir) {
            case GraphDirection.DOWN -> this.adjDown = node;
            case GraphDirection.UP -> this.adjUp = node;
            case GraphDirection.NORTH -> this.adjNorth = node;
            case GraphDirection.SOUTH -> this.adjSouth = node;
            case GraphDirection.WEST -> this.adjWest = node;
            case GraphDirection.EAST -> this.adjEast = node;
        };
    }

    /**
     * Cancels any pending tasks to rebuild the chunk. If the result of any pending tasks has not been processed yet,
     * those will also be discarded when processing finally happens.
     */
    public void cancelRebuildTask() {
        if (this.rebuildTask != null) {
            this.rebuildTask.cancel(false);
            this.rebuildTask = null;
        }
    }

    public ChunkRenderData getData() {
        return this.data;
    }

    /**
     * Deletes all data attached to this render and drops any pending tasks. This should be used when the render falls
     * out of view or otherwise needs to be destroyed. After the render has been destroyed, the object can no longer
     * be used.
     */
    public void delete() {
        this.cancelRebuildTask();
        this.setData(ChunkRenderData.ABSENT);

        this.disposed = true;
    }

    public void setData(ChunkRenderData info) {
        if (info == null) {
            throw new NullPointerException("Mesh information must not be null");
        }

        this.worldRenderer.onChunkRenderUpdated(this.chunkX, this.chunkY, this.chunkZ, this.data, info);
        this.data = info;

        this.flags = info.getFlags();
    }

    public int getFlags() {
        return this.flags;
    }

    /**
     * Returns the chunk section position which this render refers to in the world.
     */
    public ChunkSectionPos getChunkPos() {
        return ChunkSectionPos.from(this.chunkX, this.chunkY, this.chunkZ);
    }

    /**
     * Ensures that all resources attached to the given chunk render are "ticked" forward. This should be called every
     * time before this render is drawn if {@link RenderSection#getFlags()} contains {@link RenderSectionFlags#HAS_ANIMATED_SPRITES}.
     */
    public void tickAnimatedSprites() {
        for (Sprite sprite : this.data.getAnimatedSprites()) {
            SpriteUtil.markSpriteActive(sprite);
        }
    }

    /**
     * @return The x-coordinate of the origin position of this chunk render
     */
    public int getOriginX() {
        return this.chunkX << 4;
    }

    /**
     * @return The y-coordinate of the origin position of this chunk render
     */
    public int getOriginY() {
        return this.chunkY << 4;
    }

    /**
     * @return The z-coordinate of the origin position of this chunk render
     */
    public int getOriginZ() {
        return this.chunkZ << 4;
    }

    /**
     * @return The squared distance from the center of this chunk in the world to the center of the block position
     * given by {@param pos}
     */
    public float getSquaredDistance(BlockPos pos) {
        return this.getSquaredDistance(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
    }

    /**
     * @return The squared distance from the center of this chunk in the world to the given position
     */
    public float getSquaredDistance(float x, float y, float z) {
        float xDist = x - this.getCenterX();
        float yDist = y - this.getCenterY();
        float zDist = z - this.getCenterZ();

        return (xDist * xDist) + (yDist * yDist) + (zDist * zDist);
    }

    /**
     * @return The x-coordinate of the center position of this chunk render
     */
    private float getCenterX() {
        return this.getOriginX() + 8.0f;
    }

    /**
     * @return The y-coordinate of the center position of this chunk render
     */
    private float getCenterY() {
        return this.getOriginY() + 8.0f;
    }

    /**
     * @return The z-coordinate of the center position of this chunk render
     */
    private float getCenterZ() {
        return this.getOriginZ() + 8.0f;
    }

    /**
     * @return The squared distance from the center of this chunk in the world to the given position
     */
    public float getSquaredDistanceXZ(float x, float z) {
        float xDist = x - this.getCenterX();
        float zDist = z - this.getCenterZ();

        return (xDist * xDist) + (zDist * zDist);
    }

    public int getChunkX() {
        return this.chunkX;
    }

    public int getChunkY() {
        return this.chunkY;
    }

    public int getChunkZ() {
        return this.chunkZ;
    }

    public ChunkRenderBounds getBounds() {
        return this.data.getBounds();
    }

    public boolean isDisposed() {
        return this.disposed;
    }

    public ChunkUpdateType getPendingUpdate() {
        return this.pendingUpdate;
    }

    public void markForUpdate(ChunkUpdateType type) {
        if (this.pendingUpdate == null || type.ordinal() > this.pendingUpdate.ordinal()) {
            this.pendingUpdate = type;
        }
    }

    public void onBuildSubmitted(CompletableFuture<?> task) {
        if (this.rebuildTask != null) {
            this.rebuildTask.cancel(false);
            this.rebuildTask = null;
        }

        this.rebuildTask = task;
        this.pendingUpdate = null;
    }

    public boolean isBuilt() {
        return this.data != ChunkRenderData.ABSENT;
    }

    public boolean canAcceptBuildResults(ChunkBuildResult result) {
        return !this.isDisposed() && result.buildTime > this.lastAcceptedBuildTime;
    }

    public void onBuildFinished(ChunkBuildResult result) {
        this.setData(result.data);
        this.lastAcceptedBuildTime = result.buildTime;
    }

    public int getChunkId() {
        return this.chunkId;
    }

    public long getRegionId() {
        return this.regionId;
    }

    @Deprecated
    public void setLastVisibleFrame(int frame) {
        this.lastVisibleFrame = frame;
    }

    @Deprecated
    public int getLastVisibleFrame() {
        return this.lastVisibleFrame;
    }

    @Deprecated // reason: will become internal-only
    public void setOcclusionData(ChunkOcclusionData occlusionData) {
        this.visibilityData = calculateVisibilityData(occlusionData);
    }

    private static long calculateVisibilityData(ChunkOcclusionData occlusionData) {
        long visibilityData = 0;

        for (Direction from : DirectionUtil.ALL_DIRECTIONS) {
            for (Direction to : DirectionUtil.ALL_DIRECTIONS) {
                if (occlusionData == null || occlusionData.isVisibleThrough(from, to)) {
                    visibilityData |= (1L << ((from.ordinal() << 3) + to.ordinal()));
                }
            }
        }

        return visibilityData;
    }

    public boolean isVisibleThrough(int incomingDirection, int outgoingDirection) {
        if (incomingDirection == GraphDirection.NONE) {
            return true;
        }

        return ((this.visibilityData & (1L << ((incomingDirection << 3) + outgoingDirection))) != 0L);
    }

    public boolean isCulledByFrustum(Frustum frustum) {
        float x = this.getOriginX();
        float y = this.getOriginY();
        float z = this.getOriginZ();

        return !frustum.isBoxVisible(x, y, z, x + 16.0f, y + 16.0f, z + 16.0f);
    }

    @Override
    public String toString() {
        return String.format("RenderChunk{chunkX=%d, chunkY=%d, chunkZ=%d}",
                this.chunkX, this.chunkY, this.chunkZ);
    }
}
