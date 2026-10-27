package com.jianguo.mistep;

/** Physical left native player, physical right lyrics, with the original full card height. */
final class MediaPanelGeometry {
    final int playerWidth, lyricLeft, lyricRight, lyricTop, lyricBottom;
    MediaPanelGeometry(int width,int height,int gap,int padding) {
        int w=Math.max(0,width),h=Math.max(0,height),g=Math.min(w,Math.max(0,gap));
        playerWidth=(w-g)/2;
        lyricLeft=playerWidth+g;
        lyricRight=Math.max(lyricLeft,w-Math.min(w,Math.max(0,padding)));
        lyricTop=Math.min(h/2,Math.max(0,padding));
        lyricBottom=Math.max(lyricTop,h-lyricTop);
    }
}
