package com.jianguo.mistep;

/** Divide only the existing header gap; the cover, output control and card height stay native. */
final class MediaHeaderGeometry {
    final int metadataWidth, lyricWidth;
    MediaHeaderGeometry(int available, int gap) {
        int width=Math.max(0,available), space=Math.min(width,Math.max(0,gap));
        metadataWidth=Math.max(0,Math.round((width-space)*.44f));
        lyricWidth=Math.max(0,width-space-metadataWidth);
    }
}
