package com.jiusis.mistep;

/** Keep the complete icon and caption inside the tile's allocated pixel bounds. */
final class ControlCenterTileGeometry {
    final int iconSize, iconTop, captionTop, captionHeight;

    ControlCenterTileGeometry(int width, int height, int preferredIcon,
            int preferredCaption, int preferredGap) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        captionHeight = Math.min(Math.max(1, preferredCaption), Math.max(1, height / 2));
        int gap = Math.max(0, Math.min(preferredGap, height - captionHeight - 1));
        iconSize = Math.max(0, Math.min(Math.max(0, preferredIcon),
                Math.min(width, height - captionHeight - gap)));
        if (iconSize == 0) gap = 0;
        iconTop = (height - iconSize - gap - captionHeight) / 2;
        captionTop = iconTop + iconSize + gap;
    }
}
