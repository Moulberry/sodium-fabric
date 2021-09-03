package me.jellysquid.mods.sodium.client.model.quad.blender;

import me.jellysquid.mods.sodium.client.model.quad.QuadColorizer;
import me.jellysquid.mods.sodium.client.util.color.ColorMixer;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

public class SmoothBiomeColorBlender implements BiomeColorBlender {
    private final int[] cachedRet = new int[4];

    private final BlockPos.Mutable mpos = new BlockPos.Mutable();

    @Override
    public <T> int[] getColors(BlockRenderView world, BlockPos origin, QuadView quad, QuadColorizer<T> colorizer, T state) {
        final int[] colors = this.cachedRet;

        boolean aligned = true;

        for (int i = 0; i < 4; i++) {
            // If the vertex is aligned to the block grid, we do not need to interpolate
            if (aligned) {
                colors[i] = this.getVertexColor(colorizer, world, state, origin, quad, i);
            } else {
                colors[i] = this.getInterpolatedVertexColor(colorizer, world, state, origin, quad, i);
            }
        }

        return colors;
    }

    private <T> int getVertexColor(QuadColorizer<T> colorizer, BlockRenderView world, T state, BlockPos origin,
                                   QuadView quad, int vertexIdx) {
        final int x = origin.getX() + (int) quad.x(vertexIdx);
        final int z = origin.getZ() + (int) quad.z(vertexIdx);

        return this.getBlockColor(colorizer, world, state, origin, x, z, quad.colorIndex());
    }

    private <T> int getBlockColor(QuadColorizer<T> colorizer, BlockRenderView world, T state, BlockPos origin,
                                  int x, int z, int colorIdx) {
        return colorizer.getColor(state, world, this.mpos.set(x, origin.getY(), z), colorIdx);
    }

    private <T> int getInterpolatedVertexColor(QuadColorizer<T> colorizer, BlockRenderView world, T state,
                                               BlockPos origin, QuadView quad, int vertexIdx) {
        final float x = quad.x(vertexIdx);
        final float z = quad.z(vertexIdx);

        final int intX = (int) x;
        final int intZ = (int) z;

        // Integer component of position vector
        final int originX = origin.getX() + intX;
        final int originZ = origin.getZ() + intZ;

        // Retrieve the color values for each neighbor
        final int c1 = this.getBlockColor(colorizer, world, state, origin, originX, originZ, quad.colorIndex());
        final int c2 = this.getBlockColor(colorizer, world, state, origin, originX, originZ + 1, quad.colorIndex());
        final int c3 = this.getBlockColor(colorizer, world, state, origin, originX + 1, originZ, quad.colorIndex());
        final int c4 = this.getBlockColor(colorizer, world, state, origin, originX + 1, originZ + 1, quad.colorIndex());

        final int result;

        // All the colors are the same, so the results of interpolation will be useless.
        if (c1 == c2 && c2 == c3 && c3 == c4) {
            result = c1;
        } else {
            // Fraction component of position vector
            final float fracX = x - intX;
            final float fracZ = z - intZ;

            int z1 = ColorMixer.getStartRatio(fracZ);
            int z2 = ColorMixer.getEndRatio(fracZ);

            int r1 = ColorMixer.mixARGB(c1, c2, z1, z2);
            int r2 = ColorMixer.mixARGB(c3, c4, z1, z2);

            int x1 = ColorMixer.getStartRatio(fracX);
            int x2 = ColorMixer.getEndRatio(fracX);

            result = ColorMixer.mixARGB(r1, r2, x1, x2);
        }

        return result;
    }

}
