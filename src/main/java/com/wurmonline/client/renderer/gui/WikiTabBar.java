package com.wurmonline.client.renderer.gui;

import com.wurmonline.client.renderer.PickData;
import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.gui.text.TextFont;

/** Back button plus the Search / Most searched / Recent tabs. */
final class WikiTabBar extends WikiComponent {

    interface Listener { void tabClicked(int tab); }   // -1 = back, 0 = search, 1 = most searched, 2 = recent

    private static final String[] NAMES = {"< Back", "Search", "Most searched", "Recent"};
    private static final int GAP = 4, PAD = 14;

    private final Listener listener;
    private final int[] tx = new int[4], tw = new int[4];
    private int active, hover = -2, press = -2;
    private boolean backEnabled;

    WikiTabBar(Listener listener) {
        super("wiki.tabs");
        this.listener = listener;
        setSize(400, 34);
        sizeFlags = FIXED_HEIGHT;
    }

    void setActive(int tab) { active = tab; }

    void setBackEnabled(boolean enabled) { backEnabled = enabled; }

    private void place() {
        TextFont f = bold();
        int cx = 6;
        for (int i = 0; i < 4; i++) {
            tw[i] = f.getWidth(NAMES[i]) + 2 * PAD;
            tx[i] = cx;
            cx += tw[i] + GAP;
        }
    }

    /** -1 = back, 0..2 = tabs, -2 = nothing. */
    private int tabAt(int mx, int my) {
        place();
        int lx = mx - x, ly = my - y;
        if (ly < 4 || ly > height - 2) return -2;
        for (int i = 0; i < 4; i++) if (lx >= tx[i] && lx < tx[i] + tw[i]) return i - 1;
        return -2;
    }

    private static final float[] WOOD_TOP = {0.545f, 0.420f, 0.270f}, WOOD_BOT = {0.372f, 0.267f, 0.161f};
    private static final float[] HOT_TOP = {0.640f, 0.500f, 0.322f}, HOT_BOT = {0.440f, 0.318f, 0.184f};
    private static final float[] ON_TOP = {0.824f, 0.651f, 0.353f}, ON_BOT = {0.608f, 0.435f, 0.173f};
    private static final float[] OFF_TOP = {0.250f, 0.200f, 0.140f}, OFF_BOT = {0.190f, 0.150f, 0.110f};

    @Override
    protected void renderComponent(Queue queue, float ignoredAlpha) {
        place();
        TextFont f = bold();
        fill(queue, x, y, width, height, 0.082f, 0.059f, 0.039f);
        fill(queue, x, y + height - 2, width, 2, 0.79f, 0.60f, 0.29f);      // gold line under the tabs
        for (int i = 0; i < 4; i++) {
            int id = i - 1;
            boolean isBack = id == -1;
            boolean on = !isBack && id == active;
            boolean disabled = isBack && !backEnabled;
            boolean hot = id == hover && !disabled;
            float[] top = on ? ON_TOP : disabled ? OFF_TOP : hot ? HOT_TOP : WOOD_TOP;
            float[] bot = on ? ON_BOT : disabled ? OFF_BOT : hot ? HOT_BOT : WOOD_BOT;

            int px = x + tx[i], ty = y + 4, h = height - 6;
            fill(queue, px, ty, tw[i], h, 0.11f, 0.075f, 0.045f);              // dark edge
            int inner = h - 1, half = inner / 2;
            fill(queue, px + 1, ty + 1, tw[i] - 2, half, top[0], top[1], top[2]);          // lighter upper half
            fill(queue, px + 1, ty + 1 + half, tw[i] - 2, inner - half, bot[0], bot[1], bot[2]);
            if (!disabled)                                                      // bevel highlight
                fill(queue, px + 1, ty + 1, tw[i] - 2, 1, on ? 0.92f : 0.73f, on ? 0.80f : 0.59f, on ? 0.55f : 0.42f);

            int textTop = ty + (h - f.getHeight()) / 2;
            if (disabled) {
                label(queue, f, NAMES[i], px + PAD, textTop, 0.45f, 0.40f, 0.34f);
            } else if (on) {
                label(queue, f, NAMES[i], px + PAD, textTop, 0.11f, 0.07f, 0.03f);
            } else {
                label(queue, f, NAMES[i], px + PAD + 1, textTop + 1, 0f, 0f, 0f);   // soft text shadow
                label(queue, f, NAMES[i], px + PAD, textTop, 0.95f, 0.91f, 0.82f);
            }
        }
    }

    @Override public void pick(PickData data, int mx, int my) {
        super.pick(data, mx, my);
        hover = tabAt(mx, my);
    }

    @Override protected void mouseMoved(int mx, int my) { hover = tabAt(mx, my); }

    @Override protected void mouseExited() { hover = -2; }

    @Override protected void leftPressed(int mx, int my, int clickCount) { press = tabAt(mx, my); }

    @Override protected void leftReleased(int mx, int my) {
        int t = tabAt(mx, my);
        boolean fire = t != -2 && t == press && !(t == -1 && !backEnabled);
        press = -2;
        if (fire && listener != null) listener.tabClicked(t);
    }
}
