package com.wurmonline.client.renderer.gui;

import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.gui.text.TextFont;

/** A fixed brown section bar (same look as the bars on a wiki page) used above the search box. */
final class WikiHeaderBar extends WikiComponent {

    private final String title;

    WikiHeaderBar(String title) {
        super("wiki.header");
        this.title = title;
        setSize(300, bold().getHeight() + 12);
        sizeFlags = FIXED_HEIGHT;
    }

    int barHeight() { return bold().getHeight() + 12; }

    @Override
    protected void renderComponent(Queue queue, float ignoredAlpha) {
        TextFont f = bold();
        fill(queue, x, y, width, height, 0.29f, 0.23f, 0.18f);
        fill(queue, x, y, width, 1, 0.47f, 0.38f, 0.28f);                       // top highlight
        label(queue, f, title, x + (width - f.getWidth(title)) / 2, y + (height - f.getHeight()) / 2,
                0.96f, 0.92f, 0.82f);
    }
}
