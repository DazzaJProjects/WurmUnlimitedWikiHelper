package com.wurmonline.client.renderer.gui;

import com.wurmonline.client.renderer.PickData;
import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.gui.text.TextFont;
import com.wurmonline.client.resources.textures.ResourceTexture;
import wurmwiki.WikiDatabase;
import wurmwiki.WikiImages;
import wurmwiki.WikiMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a wiki page (or a list of entries) in the style of the wiki: title plate, brown section bars over
 * parchment panels, a framed picture, contents list, article text, striped tables, and clickable links.
 * It is a single component that lays itself out, so it scrolls inside a normal scroll panel.
 */
final class WikiView extends WikiComponent {

    interface Listener { void openPage(String target); }

    static final int TITLE = 0, HEADER = 1, IMAGE = 2, LINE = 3, NOTE = 4, ROW = 5, HINT = 6, GAP = 7,
            H2 = 8, H3 = 9, PARA = 10, BULLET = 11, NUM = 12, TABLE = 13, TOC = 14;

    static final class Block {
        final int kind;
        String text = "", right = "", target;
        int gap, level, idx;
        WikiDatabase.Page page;
        List<String[]> rows;
        boolean[] hdr;
        Block(int kind) { this.kind = kind; }
    }

    static Block title(String s) { Block b = new Block(TITLE); b.text = s; return b; }
    static Block header(String s) { Block b = new Block(HEADER); b.text = s; return b; }
    static Block image(WikiDatabase.Page p) { Block b = new Block(IMAGE); b.page = p; return b; }
    static Block line(String markup) { Block b = new Block(LINE); b.text = markup; return b; }
    static Block note(String s) { Block b = new Block(NOTE); b.text = s; return b; }
    static Block hint(String s) { Block b = new Block(HINT); b.text = s; return b; }
    static Block gap(int px) { Block b = new Block(GAP); b.gap = px; return b; }
    static Block row(String label, String right, String target) {
        Block b = new Block(ROW); b.text = label; b.right = right; b.target = target; return b;
    }
    static Block h2(String s, int idx) { Block b = new Block(H2); b.text = s; b.idx = idx; return b; }
    static Block h3(String s, int idx) { Block b = new Block(H3); b.text = s; b.idx = idx; return b; }
    static Block para(String markup) { Block b = new Block(PARA); b.text = markup; return b; }
    static Block bullet(String markup, int level) { Block b = new Block(BULLET); b.text = markup; b.level = level; return b; }
    static Block num(String markup, int level) { Block b = new Block(NUM); b.text = markup; b.level = level; return b; }
    static Block toc(String number, String title, int idx, int level) {
        Block b = new Block(TOC); b.text = number; b.right = title; b.idx = idx; b.level = level; return b;
    }
    static Block table(List<String[]> rows, List<Boolean> header) {
        Block b = new Block(TABLE);
        b.rows = rows;
        b.hdr = new boolean[rows.size()];
        for (int i = 0; i < b.hdr.length; i++) b.hdr[i] = header.get(i);
        return b;
    }

    // ---------------------------------------------------------------- palette

    private static final float[] PARCHMENT = {0.925f, 0.898f, 0.839f};
    private static final float[] STRIPE = {0.886f, 0.855f, 0.780f};
    private static final float[] GRID = {0.75f, 0.69f, 0.57f};
    private static final float[] PANEL_EDGE = {0.29f, 0.23f, 0.18f};
    private static final float[] BAR = {0.29f, 0.23f, 0.18f};
    private static final float[] BAR_LIGHT = {0.47f, 0.38f, 0.28f};
    private static final float[] GOLD = {0.94f, 0.82f, 0.50f};
    private static final float[] CREAM = {0.96f, 0.92f, 0.82f};
    private static final float[] INK = {0.18f, 0.13f, 0.09f};
    private static final float[] MUTED = {0.38f, 0.31f, 0.24f};
    private static final float[] LINK = {0.09f, 0.28f, 0.61f};
    private static final float[] LINK_HOT = {0.27f, 0.51f, 0.90f};
    private static final float[] DIM_CREAM = {0.84f, 0.78f, 0.66f};
    private static final float[] TOP_LINK = {0.78f, 0.69f, 0.55f};

    private static final int MARGIN = 10, PAD = 12, PICTURE = 160;
    private static final int D_RECT = 0, D_TEXT = 1, D_IMAGE = 2;

    private static final class Draw {
        final int type;
        int x, y, w, h, hit = -1;
        String s;
        boolean bold, onlyHover;
        float r, g, b, hr, hg, hb;
        WikiDatabase.Page page;
        Draw(int type) { this.type = type; }
    }

    private static final class Hit {
        int x, y, w, h, id;
        String target;
    }

    private static final class Tok {
        String word, target;
        int hit = -1;
        boolean space;
    }

    // ---------------------------------------------------------------- state

    private final List<Block> blocks;
    private final Listener listener;
    private final List<Draw> draws = new ArrayList<Draw>();
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<Integer, Integer> sections = new HashMap<Integer, Integer>();
    private int lastWidth = -1, hoverHit = -1, pressHit = -1, nextHit;

    WikiView(List<Block> blocks, Listener listener) {
        super("wiki.view");
        this.blocks = blocks;
        this.listener = listener;
        setSize(480, 100);
        sizeFlags = FIXED_HEIGHT;
        lastWidth = width;
        relayout();
    }

    /** Vertical position of a heading, for the contents list to jump to. */
    int sectionY(int idx) {
        Integer v = sections.get(idx);
        return v == null ? 0 : v;
    }

    @Override
    void componentResized() {
        super.componentResized();
        if (draws == null || blocks == null || width == lastWidth) return;
        lastWidth = width;
        relayout();
    }

    // ---------------------------------------------------------------- layout

    private void relayout() {
        draws.clear();
        hits.clear();
        sections.clear();
        nextHit = 0;
        final int cw = Math.max(160, width - 2 * MARGIN);
        final TextFont f = body(), fb = bold();
        final int lineH = f.getHeight() + 6;
        final int left = MARGIN + PAD, right = MARGIN + cw - PAD;

        int y = MARGIN;
        boolean group = false;
        int groupIdx = 0, groupTop = 0, n0 = 0, n1 = 0;

        for (Block b : blocks) {
            boolean inPanel = b.kind == IMAGE || b.kind == LINE || b.kind == NOTE || b.kind == PARA || b.kind == BULLET
                    || b.kind == NUM || b.kind == H3 || b.kind == TABLE || b.kind == TOC;
            if (group && !inPanel) { y = closePanel(groupIdx, groupTop, y, cw); group = false; }
            if (inPanel && !group) { group = true; groupIdx = draws.size(); groupTop = y; y += 8; }
            if (b.kind != NUM) { n0 = 0; n1 = 0; }

            switch (b.kind) {
                case TITLE: {
                    int h = fb.getHeight() + 18;
                    rect(MARGIN, y, cw, h, 0.59f, 0.46f, 0.26f);
                    rect(MARGIN + 2, y + 2, cw - 4, h - 4, 0.19f, 0.145f, 0.105f);
                    text(b.text, MARGIN + (cw - fb.getWidth(b.text)) / 2, y + (h - fb.getHeight()) / 2,
                            true, GOLD, GOLD, -1);
                    y += h + 8;
                    break;
                }
                case HEADER: {
                    int h = fb.getHeight() + 12;
                    rect(MARGIN, y, cw, h, BAR[0], BAR[1], BAR[2]);
                    rect(MARGIN, y, cw, 1, BAR_LIGHT[0], BAR_LIGHT[1], BAR_LIGHT[2]);
                    text(b.text, MARGIN + (cw - fb.getWidth(b.text)) / 2, y + (h - fb.getHeight()) / 2,
                            true, CREAM, CREAM, -1);
                    y += h;
                    break;
                }
                case H2: {
                    sections.put(b.idx, y);
                    int h = fb.getHeight() + 12;
                    rect(MARGIN, y, cw, h, BAR[0], BAR[1], BAR[2]);
                    rect(MARGIN, y, cw, 1, BAR_LIGHT[0], BAR_LIGHT[1], BAR_LIGHT[2]);
                    text(b.text, MARGIN + PAD, y + (h - fb.getHeight()) / 2, true, CREAM, CREAM, -1);
                    String top = "[top]";
                    int tw = f.getWidth(top), id = nextHit++, tx = MARGIN + cw - PAD - tw;
                    text(top, tx, y + (h - f.getHeight()) / 2, false, TOP_LINK, CREAM, id);
                    Hit hh = new Hit();
                    hh.x = tx - 4; hh.y = y; hh.w = tw + 8; hh.h = h; hh.id = id; hh.target = "#top";
                    hits.add(hh);
                    y += h;
                    break;
                }
                case IMAGE: {
                    int outer = PICTURE + 8;
                    int ox = MARGIN + (cw - outer) / 2;
                    rect(ox, y, outer, outer, 0.38f, 0.30f, 0.21f);
                    rect(ox + 2, y + 2, outer - 4, outer - 4, 0.13f, 0.105f, 0.08f);
                    Draw d = new Draw(D_IMAGE);
                    d.x = ox + 4; d.y = y + 4; d.w = PICTURE; d.h = PICTURE; d.page = b.page;
                    draws.add(d);
                    y += outer + 8;
                    break;
                }
                case LINE: {
                    rect(left, y + lineH / 2 - 2, 4, 4, INK[0], INK[1], INK[2]);
                    y = flow(WikiDatabase.segments(b.text), left + 12, right, y, lineH, f, INK, true, false);
                    y += 3;
                    break;
                }
                case NOTE: {
                    y = flow(WikiDatabase.segments(b.text), left, right, y, lineH, f, MUTED, false, false);
                    y += 2;
                    break;
                }
                case PARA: {
                    y = flow(WikiDatabase.segments(b.text), left, right, y, lineH, f, INK, true, false);
                    y += 5;
                    break;
                }
                case BULLET: {
                    int bx = left + b.level * 18;
                    rect(bx, y + lineH / 2 - 2, 4, 4, INK[0], INK[1], INK[2]);
                    y = flow(WikiDatabase.segments(b.text), bx + 12, right, y, lineH, f, INK, true, false);
                    y += 2;
                    break;
                }
                case NUM: {
                    int nx = left + b.level * 18;
                    String label = b.level == 0 ? (++n0) + "." : (++n1) + ".";
                    if (b.level == 0) n1 = 0;
                    text(label, nx, y + 3, false, INK, INK, -1);
                    y = flow(WikiDatabase.segments(b.text), nx + 24, right, y, lineH, f, INK, true, false);
                    y += 2;
                    break;
                }
                case H3: {
                    sections.put(b.idx, y);
                    y += 6;
                    text(b.text, left, y, true, INK, INK, -1);
                    y += fb.getHeight() + 3;
                    rect(left, y, right - left, 1, 0.70f, 0.64f, 0.52f);
                    y += 7;
                    break;
                }
                case TOC: {
                    List<WikiDatabase.Seg> segs = new ArrayList<WikiDatabase.Seg>();
                    segs.add(new WikiDatabase.Seg(b.text + "  ", null));
                    segs.add(new WikiDatabase.Seg(b.right, "#sec:" + b.idx));
                    y = flow(segs, left + b.level * 18, right, y, lineH - 2, f, INK, true, false);
                    break;
                }
                case TABLE:
                    y = table(b, y, cw, f, fb, lineH);
                    break;
                case ROW: {
                    int h = fb.getHeight() + 18;
                    int id = nextHit++;
                    rect(MARGIN, y, cw, h, 0.11f, 0.075f, 0.045f);                       // dark edge
                    int inner = h - 2, half = inner / 2;
                    hoverRect(MARGIN + 1, y + 1, cw - 2, half, id, 0.365f, 0.275f, 0.188f, 0.478f, 0.361f, 0.227f);
                    hoverRect(MARGIN + 1, y + 1 + half, cw - 2, inner - half, id, 0.275f, 0.200f, 0.122f, 0.361f, 0.263f, 0.157f);
                    rect(MARGIN + 1, y + 1, cw - 2, 1, 0.48f, 0.37f, 0.25f);             // top highlight
                    text(b.text, MARGIN + 14, y + (h - fb.getHeight()) / 2, true, CREAM, GOLD, id);
                    if (!b.right.isEmpty())
                        text(b.right, MARGIN + cw - 14 - f.getWidth(b.right), y + (h - f.getHeight()) / 2,
                                false, DIM_CREAM, DIM_CREAM, id);
                    Hit h2 = new Hit();
                    h2.x = MARGIN; h2.y = y; h2.w = cw; h2.h = h; h2.id = id; h2.target = b.target;
                    hits.add(h2);
                    y += h + 5;
                    break;
                }
                case HINT: {
                    y = flow(WikiDatabase.segments(b.text), MARGIN + 4, MARGIN + cw - 4, y, lineH, f, DIM_CREAM, false, false);
                    y += 4;
                    break;
                }
                default:
                    y += b.gap;
            }
        }
        if (group) y = closePanel(groupIdx, groupTop, y, cw);

        int desired = Math.max(40, y + MARGIN);
        if (height != desired) {
            int flags = sizeFlags;
            sizeFlags = 0;
            setSize(width, desired);
            sizeFlags = flags;
        }
    }

    /** Puts the parchment panel behind everything added since the group started. */
    private int closePanel(int index, int top, int y, int cw) {
        y += 8;
        Draw edge = new Draw(D_RECT), fill = new Draw(D_RECT);
        edge.x = MARGIN; edge.y = top; edge.w = cw; edge.h = y - top;
        edge.r = PANEL_EDGE[0]; edge.g = PANEL_EDGE[1]; edge.b = PANEL_EDGE[2];
        fill.x = MARGIN + 1; fill.y = top; fill.w = cw - 2; fill.h = y - top - 1;
        fill.r = PARCHMENT[0]; fill.g = PARCHMENT[1]; fill.b = PARCHMENT[2];
        draws.add(index, edge);
        draws.add(index + 1, fill);
        return y + 10;
    }

    /**
     * A table whose columns fit the window: each column gets its natural width if everything fits, otherwise
     * long text wraps inside its cell. Rows are striped and the header row is a brown bar.
     */
    private int table(Block b, int y, int cw, TextFont f, TextFont fb, int lineH) {
        final int left = MARGIN + PAD, avail = cw - 2 * PAD, pad = 6;
        int cols = 0;
        for (String[] r : b.rows) cols = Math.max(cols, r.length);
        if (cols == 0 || avail < 80) return y;

        int[] nat = new int[cols], min = new int[cols];
        for (int r = 0; r < b.rows.size(); r++) {
            TextFont tf = b.hdr[r] ? fb : f;
            String[] row = b.rows.get(r);
            for (int c = 0; c < row.length; c++) {
                String plain = WikiDatabase.strip(row[c]).trim();
                if (plain.isEmpty()) continue;
                nat[c] = Math.max(nat[c], tf.getWidth(plain));
                for (String w : plain.split(" ")) min[c] = Math.max(min[c], tf.getWidth(w));
            }
        }
        long natTotal = 0, minTotal = 0;
        for (int c = 0; c < cols; c++) { nat[c] += 2 * pad; min[c] += 2 * pad; natTotal += nat[c]; minTotal += min[c]; }

        int[] w = new int[cols];
        if (natTotal <= avail) {
            int extra = (int) ((avail - natTotal) / cols);
            for (int c = 0; c < cols; c++) w[c] = nat[c] + extra;
        } else if (minTotal >= avail) {
            for (int c = 0; c < cols; c++) w[c] = Math.max(20, (int) (min[c] * (long) avail / minTotal));
        } else {
            long spare = avail - minTotal, want = Math.max(1, natTotal - minTotal);
            for (int c = 0; c < cols; c++) w[c] = min[c] + (int) (spare * (nat[c] - min[c]) / want);
        }
        int sum = 0;
        for (int c = 0; c < cols; c++) sum += w[c];
        w[cols - 1] = Math.max(20, w[cols - 1] + avail - sum);
        int[] x = new int[cols + 1];
        x[0] = left;
        for (int c = 0; c < cols; c++) x[c + 1] = x[c] + w[c];

        int top = y, dataRow = 0;
        rect(left, y, avail, 1, GRID[0], GRID[1], GRID[2]);
        for (int r = 0; r < b.rows.size(); r++) {
            boolean hd = b.hdr[r];
            TextFont tf = hd ? fb : f;
            String[] row = b.rows.get(r);
            int bgIdx = draws.size(), end = y + lineH + 4;
            for (int c = 0; c < row.length; c++) {
                if (row[c].trim().isEmpty()) continue;
                int e = flow(WikiDatabase.segments(row[c]), x[c] + pad, x[c + 1] - pad, y + 2, lineH, tf,
                        hd ? CREAM : INK, !hd, hd);
                end = Math.max(end, e + 2);
            }
            int rowH = end - y;
            float[] bg = hd ? BAR : (dataRow % 2 == 0 ? PARCHMENT : STRIPE);
            if (!hd) dataRow++;
            Draw d = new Draw(D_RECT);
            d.x = left; d.y = y; d.w = avail; d.h = rowH; d.r = bg[0]; d.g = bg[1]; d.b = bg[2];
            draws.add(bgIdx, d);
            for (int c = 0; c <= cols; c++) rect(c == cols ? x[c] - 1 : x[c], y, 1, rowH, GRID[0], GRID[1], GRID[2]);
            rect(left, y + rowH - 1, avail, 1, GRID[0], GRID[1], GRID[2]);
            y += rowH;
        }
        return y + 6;
    }

    /** Word-wraps mixed plain/link text between {@code left} and {@code right}. Returns the y after the last line. */
    private int flow(List<WikiDatabase.Seg> segs, int left, int right, int y, int lineH, TextFont f,
                     float[] base, boolean links, boolean bold) {
        List<Tok> toks = new ArrayList<Tok>();
        boolean pendingSpace = false;
        for (WikiDatabase.Seg seg : segs) {
            int id = -1;
            if (links && seg.target != null && (seg.target.startsWith("#") || WikiMod.DB.resolve(seg.target) != null))
                id = nextHit++;
            String[] parts = seg.text.split(" ", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) pendingSpace = true;
                if (parts[i].isEmpty()) continue;
                Tok t = new Tok();
                t.word = parts[i];
                t.hit = id;
                t.target = id >= 0 ? seg.target : null;
                t.space = pendingSpace && !toks.isEmpty();
                toks.add(t);
                pendingSpace = false;
            }
        }
        if (toks.isEmpty()) return y;

        int sp = f.getWidth(" ");
        if (sp <= 0) sp = Math.max(3, f.getWidth("n") / 2);
        int cx = left;
        boolean lineStart = true;
        Tok prev = null;
        int prevEnd = left;
        for (Tok t : toks) {
            int w = f.getWidth(t.word);
            int gap = (t.space && !lineStart) ? sp : 0;
            if (!lineStart && cx + gap + w > right) {
                y += lineH;
                cx = left;
                lineStart = true;
                gap = 0;
                prev = null;
            }
            int px = cx + gap;
            Draw d = new Draw(D_TEXT);
            d.s = t.word; d.x = px; d.y = y + 3; d.hit = t.hit; d.bold = bold;
            float[] c = t.hit >= 0 ? LINK : base;
            d.r = c[0]; d.g = c[1]; d.b = c[2];
            d.hr = LINK_HOT[0]; d.hg = LINK_HOT[1]; d.hb = LINK_HOT[2];
            draws.add(d);
            if (t.hit >= 0) {
                int hx = (prev != null && prev.hit == t.hit) ? prevEnd : px;
                Hit h = new Hit();
                h.x = hx; h.y = y; h.w = px + w - hx; h.h = lineH; h.id = t.hit; h.target = t.target;
                hits.add(h);
                Draw u = new Draw(D_RECT);
                u.x = hx; u.y = y + 3 + f.getHeight() + 1; u.w = px + w - hx; u.h = 1;
                u.hit = t.hit; u.onlyHover = true;
                u.r = u.hr = LINK_HOT[0]; u.g = u.hg = LINK_HOT[1]; u.b = u.hb = LINK_HOT[2];
                draws.add(u);
            }
            cx = px + w;
            prevEnd = cx;
            prev = t;
            lineStart = false;
        }
        return y + lineH;
    }

    private void rect(int x, int y, int w, int h, float r, float g, float b) {
        Draw d = new Draw(D_RECT);
        d.x = x; d.y = y; d.w = w; d.h = h; d.r = r; d.g = g; d.b = b;
        draws.add(d);
    }

    private void hoverRect(int x, int y, int w, int h, int hit, float r, float g, float b, float hr, float hg, float hb) {
        Draw d = new Draw(D_RECT);
        d.x = x; d.y = y; d.w = w; d.h = h; d.hit = hit;
        d.r = r; d.g = g; d.b = b; d.hr = hr; d.hg = hg; d.hb = hb;
        draws.add(d);
    }

    private void text(String s, int x, int top, boolean bold, float[] c, float[] hot, int hit) {
        Draw d = new Draw(D_TEXT);
        d.s = s; d.x = x; d.y = top; d.bold = bold; d.hit = hit;
        d.r = c[0]; d.g = c[1]; d.b = c[2];
        d.hr = hot[0]; d.hg = hot[1]; d.hb = hot[2];
        draws.add(d);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void renderComponent(Queue queue, float ignoredAlpha) {
        for (Draw d : draws) {
            boolean hot = d.hit >= 0 && d.hit == hoverHit;
            if (d.onlyHover && !hot) continue;
            float r = hot ? d.hr : d.r, g = hot ? d.hg : d.g, b = hot ? d.hb : d.b;
            switch (d.type) {
                case D_RECT:
                    fill(queue, x + d.x, y + d.y, d.w, d.h, r, g, b);
                    break;
                case D_TEXT:
                    label(queue, d.bold ? bold() : body(), d.s, x + d.x, y + d.y, r, g, b);
                    break;
                default:
                    paintImage(queue, d);
            }
        }
    }

    private void paintImage(Queue queue, Draw d) {
        ResourceTexture tex = WikiDraw.image(d.page);
        if (tex != null) {
            picture(queue, tex, x + d.x, y + d.y, d.w, d.h);
            return;
        }
        int state = WikiImages.state(d.page);
        String msg = state == WikiImages.LOADING ? "loading picture..." : "no picture";
        TextFont f = body();
        label(queue, f, msg, x + d.x + (d.w - f.getWidth(msg)) / 2, y + d.y + (d.h - f.getHeight()) / 2,
                0.62f, 0.55f, 0.44f);
    }

    // ---------------------------------------------------------------- mouse

    private int hitAt(int mx, int my) {
        int lx = mx - x, ly = my - y;
        for (Hit h : hits) if (lx >= h.x && lx < h.x + h.w && ly >= h.y && ly < h.y + h.h) return h.id;
        return -1;
    }

    private String targetOf(int id) {
        for (Hit h : hits) if (h.id == id) return h.target;
        return null;
    }

    @Override public void pick(PickData data, int mx, int my) {
        super.pick(data, mx, my);
        hoverHit = hitAt(mx, my);
    }

    @Override protected void mouseMoved(int mx, int my) { hoverHit = hitAt(mx, my); }

    @Override protected void mouseExited() { hoverHit = -1; }

    @Override protected void leftPressed(int mx, int my, int clickCount) { pressHit = hitAt(mx, my); }

    @Override protected void leftReleased(int mx, int my) {
        int h = hitAt(mx, my);
        String target = h >= 0 && h == pressHit ? targetOf(h) : null;
        pressHit = -1;
        if (target != null && listener != null) listener.openPage(target);
    }
}
