package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;

public class ZBufferVisibilityFlags {

    private static final int STATE_OCCLUDED = 0;
    private static final int STATE_VISIBLE = 1;
    public static final int STATE_UNKNOWN = 2;
    private static final int HAS_BEEN_VISIBLE = 1 << 2;
    private static final int HAS_BEEN_OCCLUDED = 1 << 3;

    public static boolean needsVisibilityCheck(int flags) {
        int state = flags & 0b11;
        return state != STATE_VISIBLE;
    }

    public static boolean isVisible(int flags) {
        int state = flags & 0b11;
        if (state == STATE_OCCLUDED) {
            return false;
        } else if (state == STATE_VISIBLE) {
            return true;
        } else {
            return (flags & HAS_BEEN_OCCLUDED) == 0 || (flags & HAS_BEEN_VISIBLE) != 0;
        }
    }

    public static void update(RenderSection renderSection, int state) {
        int flags = renderSection.getZBufferVisibilityFlags();
        flags &= ~0b11;
        flags |= state;
        if (state == STATE_OCCLUDED) {
            flags |= HAS_BEEN_OCCLUDED;
        }
        if (state == STATE_VISIBLE) {
            flags |= HAS_BEEN_VISIBLE;
        }
        renderSection.setZBufferVisibilityFlags(flags);
    }

}
