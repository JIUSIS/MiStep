package com.jianguo.mistep;

/** Pixel bounds shared by native view measuring and small-screen checks. */
final class ControlCenterGeometry {
    final int gap, innerGap, panelPadding, networkWidth, cardTop, controlsHeight, tileWidth, tileHeight, secondRow;
    final int modelLeft, modelWidth, volumeLeft, brightnessLeft, sliderWidth;
    final int codeWidth, codeGap, scanLeft;
    ControlCenterGeometry(int width, int height, int preferredGap) {
        width = Math.max(12, width); height = Math.max(3, height);
        gap = Math.max(1, Math.min(preferredGap, Math.min(width / 24, height / 12)));
        innerGap = Math.max(1, gap * 2 / 3);
        int usable = width - 3 * gap;
        networkWidth = Math.max(1, Math.min(height, Math.round(usable * .35f)));
        controlsHeight = height;
        cardTop = (height - networkWidth) / 2;
        panelPadding = Math.max(1, Math.min(gap * 2 / 3, networkWidth / 16));
        tileWidth = Math.max(1, (networkWidth - innerGap - 2 * panelPadding) / 2);
        modelWidth = Math.max(1, Math.round(usable * .19f));
        // Match the combined slider group to the four-button card, leaving the
        // device name and scanner on the physical screen centerline.
        sliderWidth = Math.max(1, (networkWidth - gap) / 2);
        tileHeight = Math.max(1, (networkWidth - innerGap - 2 * panelPadding) / 2);
        secondRow = cardTop + panelPadding + tileHeight + innerGap;
        modelLeft = (width - modelWidth) / 2;
        brightnessLeft = width - sliderWidth;
        volumeLeft = brightnessLeft - sliderWidth - gap;
        codeGap = Math.max(1, gap / 2);
        codeWidth = Math.max(1, (width - 2 * networkWidth - 2 * gap - 2 * codeGap) / 3);
        scanLeft = (width - codeWidth) / 2;
    }
    int tileLeft(int index) { return panelPadding + (index % 2) * (tileWidth + innerGap); }
    int tileTop(int index) { return index < 2 ? cardTop + panelPadding : secondRow; }
    int connectivityIconSize(int preferred) {
        return Math.max(1, Math.min(preferred, Math.round(networkWidth * .38f)));
    }
    int connectivityLeft(int index, int size) {
        float space = (networkWidth - 2 * size) / 3f;
        return Math.round(space + (index % 2) * (size + space));
    }
    int connectivityTop(int index, int size) {
        return cardTop + connectivityLeft(index / 2, size);
    }
    static float progressCapRadius(float width, float fillHeight, float preferredRadius) {
        return Math.max(0f, Math.min(preferredRadius, Math.min(width, fillHeight) / 2f));
    }
}
