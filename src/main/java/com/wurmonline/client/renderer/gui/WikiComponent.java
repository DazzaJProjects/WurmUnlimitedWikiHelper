package com.wurmonline.client.renderer.gui;

import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.gui.text.TextFont;
import com.wurmonline.client.resources.textures.ResourceTexture;

/** Small drawing helpers shared by the Wiki's custom-painted components. */
abstract class WikiComponent extends FlexComponent {

    WikiComponent(String id) { super(id); }

    TextFont body() { return text != null ? text : TextFont.getText(); }

    TextFont bold() { return textBold != null ? textBold : body(); }

    /** Solid coloured box (a stretched, tinted white pixel). */
    void fill(Queue q, int x, int y, int w, int h, float r, float g, float b) {
        if (w <= 0 || h <= 0) return;
        ResourceTexture px = WikiDraw.pixel();
        if (px != null) drawTexture(q, px, r, g, b, 1f, x, y, w, h, 0, 0, 1, 1);
    }

    /** One of the 256x256 cached entry pictures. */
    void picture(Queue q, ResourceTexture t, int x, int y, int w, int h) {
        drawTexture(q, t, 1f, 1f, 1f, 1f, x, y, w, h, 0, 0, 256, 256);
    }

    /** Text with its top edge at {@code top}. */
    void label(Queue q, TextFont f, String s, int x, int top, float r, float g, float b) {
        f.moveTo(x, top + f.getAscent());
        f.paint(q, s, r, g, b, 1f);
    }
}
