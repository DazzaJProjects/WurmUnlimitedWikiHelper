import javax.imageio.ImageIO;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One-off importer: copies the Wurmpedia item pages into the Wiki Helper's pages.txt.
 *
 *   --out FILE      where to write pages.txt          (default: pages.txt)
 *   --cache DIR     where downloaded pages are kept   (default: wiki-cache)  re-runs reuse it
 *   --limit N       only the first N pages (a trial run)
 *   --delay MS      pause between downloads           (default: 250)
 *   --images DIR    also pre-download every picture into DIR (big: roughly 30-60 KB each)
 *   --titles "A,B"  import only these pages (comma-separated), e.g. a quick test set
 *   --only "Title"  fetch and show a single page (add --tree to print the infobox structure)
 */
public class WikiImporter {

    static final String HOST = "https://www.wurmpedia.com";
    static final String UA = "WikiHelperImporter/1.0 (one-off personal copy for a Wurm Unlimited client mod)";
    static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "examine", "creation", "total materials", "result", "skill and improvement", "properties"));

    // ------------------------------------------------------------------ data

    static final class Section { final String title; final List<String> lines = new ArrayList<String>(); Section(String t) { title = t; } }

    static final class Entry {
        final String name; String image = "", examine = "", lead = "", heads = "";
        final List<String> body = new ArrayList<String>();      // tagged article lines, see the README
        final List<Section> sections = new ArrayList<Section>();
        final List<String> aliases = new ArrayList<String>();
        Entry(String n) { name = n; }
    }

    static final class Node {
        final String tag; final Map<String, String> attrs; final List<Object> kids = new ArrayList<Object>(); Node parent;
        Node(String tag, Map<String, String> attrs) { this.tag = tag; this.attrs = attrs; }
        String attr(String k) { String v = attrs.get(k); return v == null ? "" : v; }
    }

    // ------------------------------------------------------------------ HTML -> tree

    static Node parseHtml(String html) throws IOException {
        final Node root = new Node("root", new HashMap<String, String>());
        final Node[] cur = {root};
        HTMLEditorKit.ParserCallback cb = new HTMLEditorKit.ParserCallback() {
            Map<String, String> attrs(MutableAttributeSet a) {
                Map<String, String> m = new HashMap<String, String>();
                for (Enumeration<?> e = a.getAttributeNames(); e.hasMoreElements(); ) {
                    Object k = e.nextElement();
                    m.put(k.toString().toLowerCase(Locale.ROOT), String.valueOf(a.getAttribute(k)));
                }
                return m;
            }
            @Override public void handleStartTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                Node n = new Node(t.toString(), attrs(a)); n.parent = cur[0]; cur[0].kids.add(n); cur[0] = n;
            }
            @Override public void handleEndTag(HTML.Tag t, int pos) {
                Node n = cur[0];
                while (n != null && !n.tag.equals(t.toString())) n = n.parent;
                if (n != null && n.parent != null) cur[0] = n.parent;
            }
            @Override public void handleSimpleTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                Node n = new Node(t.toString(), attrs(a)); n.parent = cur[0]; cur[0].kids.add(n);
            }
            @Override public void handleText(char[] d, int pos) { cur[0].kids.add(new String(d)); }
        };
        new ParserDelegator().parse(new StringReader(html), cb, true);
        return root;
    }

    static void descendants(Node n, String tag, List<Node> out) {
        for (Object k : n.kids) if (k instanceof Node) { Node c = (Node) k; if (c.tag.equals(tag)) out.add(c); descendants(c, tag, out); }
    }

    static List<Node> all(Node n, String tag) { List<Node> l = new ArrayList<Node>(); descendants(n, tag, l); return l; }

    static String plain(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Object k : n.kids) sb.append(k instanceof String ? (String) k : plain((Node) k));
        return sb.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    // direct rows of a table (not rows of tables nested inside cells)
    static void rowsOf(Node n, List<Node> out) {
        for (Object k : n.kids) if (k instanceof Node) {
            Node c = (Node) k;
            if (c.tag.equals("tr")) out.add(c); else if (!c.tag.equals("table")) rowsOf(c, out);
        }
    }

    static void cellsOf(Node n, List<Node> out) {
        for (Object k : n.kids) if (k instanceof Node) {
            Node c = (Node) k;
            if (c.tag.equals("td") || c.tag.equals("th")) out.add(c); else if (!c.tag.equals("table")) cellsOf(c, out);
        }
    }

    static Node infobox(Node root) {
        Node best = null; int bestScore = 0;
        for (Node t : all(root, "table")) {
            int score = 0;
            for (Node c : all(t, "th")) if (KNOWN.contains(plain(c).toLowerCase(Locale.ROOT))) score++;
            for (Node c : all(t, "td")) if (KNOWN.contains(plain(c).toLowerCase(Locale.ROOT))) score++;
            if (score > 0 && score >= bestScore) { best = t; bestScore = score; }
        }
        return best;
    }

    // ------------------------------------------------------------------ links and cells

    /** "/index.php/Iron_ribbon" -> "Iron ribbon". Null for red links, other namespaces, external links. */
    static String titleFromHref(String href) {
        if (href == null || href.isEmpty()) return null;
        String h = href.startsWith(HOST) ? href.substring(HOST.length()) : href;
        if (h.contains("redlink=1")) return null;
        String t;
        if (h.startsWith("/index.php/")) t = h.substring(11);
        else if (h.startsWith("/index.php?")) {
            int i = h.indexOf("title=");
            if (i < 0) return null;
            t = h.substring(i + 6);
            int amp = t.indexOf('&');
            if (amp >= 0) t = t.substring(0, amp);
        } else return null;
        int cut = t.indexOf('#'); if (cut >= 0) t = t.substring(0, cut);
        cut = t.indexOf('?'); if (cut >= 0) t = t.substring(0, cut);
        try { t = URLDecoder.decode(t.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return null; }
        t = t.replace('_', ' ').trim();
        return t.isEmpty() || t.indexOf(':') >= 0 ? null : t;
    }

    static String abs(String src) {
        if (src == null || src.isEmpty()) return "";
        if (src.startsWith("//")) return "https:" + src;
        return src.startsWith("/") ? HOST + src : src;
    }

    static final class Cell { final List<String> lines = new ArrayList<String>(); String img = ""; boolean links; }

    private static final class Part { final String text, target; Part(String t, String g) { text = t; target = g; } }

    private static final class Flow {
        final Cell cell = new Cell();
        final List<Part> parts = new ArrayList<Part>();
        String target;

        void text(String s) {
            s = s.replace('\u00a0', ' ').replaceAll("\\s+", " ");
            if (!s.isEmpty()) parts.add(new Part(s, target));
        }

        void newline() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); ) {
                String tg = parts.get(i).target;
                StringBuilder run = new StringBuilder();
                int j = i;
                while (j < parts.size() && Objects.equals(parts.get(j).target, tg)) run.append(parts.get(j++).text);
                String r = run.toString();
                if (tg == null) sb.append(r);
                else {
                    String lead = r.startsWith(" ") ? " " : "", trail = r.endsWith(" ") ? " " : "", core = r.trim();
                    if (core.isEmpty()) sb.append(r);
                    else {
                        cell.links = true;
                        sb.append(lead).append(core.equalsIgnoreCase(tg) && core.equals(tg) ? "[[" + tg + "]]" : "[[" + tg + "|" + core + "]]").append(trail);
                    }
                }
                i = j;
            }
            parts.clear();
            String line = sb.toString().replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) cell.lines.add(line);
        }
    }

    private static void walk(Node n, Flow f) {
        for (Object k : n.kids) {
            if (k instanceof String) { f.text((String) k); continue; }
            Node c = (Node) k;
            String t = c.tag;
            if (t.equals("script") || t.equals("style") || t.equals("sup")) continue;
            if (t.equals("br")) { f.newline(); continue; }
            if (t.equals("img")) { if (f.cell.img.isEmpty()) f.cell.img = abs(c.attr("src")); continue; }
            boolean block = t.equals("li") || t.equals("p") || t.equals("div") || t.equals("ul") || t.equals("ol");
            if (block) f.newline();
            if (t.equals("a")) {
                String prev = f.target;
                f.target = titleFromHref(c.attr("href"));
                walk(c, f);
                f.target = prev;
            } else walk(c, f);
            if (block) f.newline();
        }
        if (n.tag.equals("td") || n.tag.equals("th")) f.newline();
    }

    static Cell readCell(Node cell) { Flow f = new Flow(); walk(cell, f); f.newline(); return f.cell; }

    static String unmark(String s) { return s.replaceAll("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]", "$1"); }

    // ------------------------------------------------------------------ page -> entry

    static final Map<String, Integer> HEADERS = new TreeMap<String, Integer>();

    static Entry parsePage(String title, String html) throws IOException {
        Node root = parseHtml(html);
        Node tb = infobox(root);
        Entry e = new Entry(title);
        if (tb != null) readInfobox(e, tb, title);

        Node content = byId(root, "mw-content-text");
        bodyOf(content != null ? content : root, tb, e.body);
        trimBody(e.body);

        StringBuilder heads = new StringBuilder();
        for (String l : e.body) {
            if ((l.startsWith("H2 ") || l.startsWith("H3 ")) && heads.length() < 600) heads.append(heads.length() > 0 ? "; " : "").append(l.substring(3));
            if (e.lead.isEmpty() && l.startsWith("P ")) {
                String t = unmark(l.substring(2)).trim();
                if (t.length() >= 25) e.lead = t.length() > 300 ? t.substring(0, 300) : t;
            }
        }
        e.heads = heads.toString();
        boolean info = !e.sections.isEmpty() || !e.examine.isEmpty();
        return info || !e.body.isEmpty() ? e : null;
    }

    static void readInfobox(Entry e, Node tb, String title) {
        Section sec = null;
        List<Node> rows = new ArrayList<Node>();
        rowsOf(tb, rows);
        for (Node row : rows) {
            List<Node> cells = new ArrayList<Node>();
            cellsOf(row, cells);
            for (Node cell : cells) {
                Cell c = readCell(cell);
                String t = plain(cell);
                if (t.equalsIgnoreCase(title) && sec == null) continue;                 // title row
                boolean header = !t.isEmpty() && (cell.tag.equals("th") || (!c.links && KNOWN.contains(t.toLowerCase(Locale.ROOT))));
                if (header) {
                    String key = t.toLowerCase(Locale.ROOT);
                    Integer n = HEADERS.get(key); HEADERS.put(key, n == null ? 1 : n + 1);
                    if (key.equals("examine")) sec = null; else { sec = new Section(t); e.sections.add(sec); }
                    continue;
                }
                if (!c.img.isEmpty() && e.image.isEmpty()) e.image = c.img;
                if (sec == null) {
                    for (String l : c.lines) { String s = unmark(l).trim(); if (!s.isEmpty()) e.examine += (e.examine.isEmpty() ? "" : " ") + s; }
                } else sec.lines.addAll(c.lines);
            }
        }
        Iterator<Section> it = e.sections.iterator();
        while (it.hasNext()) if (it.next().lines.isEmpty()) it.remove();
    }

    // ------------------------------------------------------------------ article body

    static Node byId(Node n, String id) {
        for (Object k : n.kids) if (k instanceof Node) {
            Node c = (Node) k;
            if (id.equals(c.attr("id"))) return c;
            Node f = byId(c, id);
            if (f != null) return f;
        }
        return null;
    }

    static boolean holds(Node n, Node target) {
        if (target == null) return false;
        if (n == target) return true;
        for (Object k : n.kids) if (k instanceof Node && holds((Node) k, target)) return true;
        return false;
    }

    static boolean skipBlock(Node c) {
        String cls = c.attr("class"), id = c.attr("id");
        return id.equals("toc") || id.equals("catlinks") || cls.contains("toc") || cls.contains("navbox")
                || cls.contains("editsection") || cls.contains("thumb") || cls.contains("hatnote") || cls.contains("noprint")
                || cls.contains("messagebox") || cls.contains("mbox") || cls.contains("ambox") || cls.contains("cleanup")
                || cls.contains("metadata") || cls.contains("mw-jump") || cls.contains("printfooter")
                || c.tag.equals("script") || c.tag.equals("style");
    }

    static String textWithout(Node n, String skipClass) {
        StringBuilder sb = new StringBuilder();
        for (Object k : n.kids) {
            if (k instanceof String) sb.append((String) k);
            else if (!((Node) k).attr("class").contains(skipClass)) sb.append(textWithout((Node) k, skipClass));
        }
        return sb.toString();
    }

    static List<String> lines(Node n) { Flow f = new Flow(); walk(n, f); f.newline(); return f.cell.lines; }

    static void bodyOf(Node n, Node infobox, List<String> out) {
        for (Object k : n.kids) {
            if (!(k instanceof Node)) continue;
            Node c = (Node) k;
            String t = c.tag;
            if (skipBlock(c)) continue;
            if (t.matches("h[1-6]")) {
                if (t.equals("h1")) continue;
                String h = textWithout(c, "editsection").replace('\u00a0', ' ').replaceAll("\\s+", " ").replaceAll("\\[edit\\]$", "").trim();
                if (!h.isEmpty()) out.add((t.equals("h2") ? "H2 " : "H3 ") + h);
            } else if (t.equals("p") || t.equals("pre") || t.equals("blockquote")) {
                for (String l : lines(c)) if (!boilerplate(l)) out.add("P " + l);
            } else if (t.equals("ul") || t.equals("ol")) {
                list(c, 0, out);
            } else if (t.equals("dl")) {
                for (String l : lines(c)) out.add("P " + l);
            } else if (t.equals("table")) {
                if (c != infobox && !holds(c, infobox)) table(c, out);
            } else {
                bodyOf(c, infobox, out);                                              // div, section, span, ...
            }
        }
    }

    /** Breadcrumb trails ("Main / Skills / Fighting") and the wiki's "needs a cleanup" banner are not article text. */
    static boolean boilerplate(String line) {
        String t = unmark(line).trim();
        return t.startsWith("Main /") || t.startsWith("To meet Wurmpedia") || t.contains("requires a cleanup");
    }

    static void list(Node list, int depth, List<String> out) {
        boolean ordered = list.tag.equals("ol");
        for (Object k : list.kids) {
            if (!(k instanceof Node) || !((Node) k).tag.equals("li")) continue;
            Node li = (Node) k, own = new Node("li", new HashMap<String, String>());
            List<Node> nested = new ArrayList<Node>();
            for (Object kk : li.kids) {
                if (kk instanceof Node && (((Node) kk).tag.equals("ul") || ((Node) kk).tag.equals("ol"))) nested.add((Node) kk);
                else own.kids.add(kk);
            }
            String text = String.join(" ", lines(own)).trim();
            if (!text.isEmpty()) out.add((ordered ? "N" : "L") + (depth > 0 ? "2" : "") + " " + text);
            for (Node nl : nested) list(nl, depth + 1, out);
        }
    }

    static int span(Node cell, String attr) {
        try { return Math.max(1, Math.min(20, Integer.parseInt(cell.attr(attr).trim()))); } catch (NumberFormatException e) { return 1; }
    }

    /**
     * Cells go on a grid: a cell that spans several rows (rowspan) leaves a blank in the rows below it, and a wide cell
     * (colspan) leaves blanks to its right, so every row lines up with the header.
     */
    static void table(Node t, List<String> out) {
        List<Node> rows = new ArrayList<Node>();
        rowsOf(t, rows);
        List<Integer> carry = new ArrayList<Integer>();          // rows still covered by a rowspan, per column
        for (Node row : rows) {
            List<Node> cells = new ArrayList<Node>();
            cellsOf(row, cells);
            List<String> grid = new ArrayList<String>();
            boolean header = !cells.isEmpty();
            int col = 0;
            for (Node cell : cells) {
                while (col < carry.size() && carry.get(col) > 0) { carry.set(col, carry.get(col) - 1); grid.add(""); col++; }
                if (!cell.tag.equals("th")) header = false;
                String text = String.join(" ", lines(cell)).replace('\t', ' ').trim();
                int cs = span(cell, "colspan"), rs = span(cell, "rowspan");
                for (int k = 0; k < cs; k++) {
                    grid.add(k == 0 ? text : "");
                    while (carry.size() <= col) carry.add(0);
                    carry.set(col, rs - 1);
                    col++;
                }
            }
            while (col < carry.size() && carry.get(col) > 0) { carry.set(col, carry.get(col) - 1); grid.add(""); col++; }
            boolean any = false;
            for (String g : grid) if (!g.isEmpty()) any = true;
            if (!any) continue;
            out.add((header ? "TH " : "TR ") + String.join("\t", grid));
        }
    }

    /** Drops headings that have nothing under them at the very end (e.g. an empty "See also"). */
    static void trimBody(List<String> body) {
        while (!body.isEmpty() && (body.get(body.size() - 1).startsWith("H2 ") || body.get(body.size() - 1).startsWith("H3 ")))
            body.remove(body.size() - 1);
    }

    static void tree(Node n, String indent, StringBuilder out) {
        for (Object k : n.kids) {
            if (k instanceof String) { String s = ((String) k).trim(); if (!s.isEmpty()) out.append(indent).append('"').append(s.length() > 50 ? s.substring(0, 50) + "..." : s).append("\"\n"); }
            else { Node c = (Node) k; out.append(indent).append('<').append(c.tag).append(c.attr("class").isEmpty() ? "" : " class=" + c.attr("class")).append(">\n"); tree(c, indent + "  ", out); }
        }
    }

    // ------------------------------------------------------------------ index pages

    /** Fills titles from one Special:AllPages page; returns the "next page" URL or null. */
    static String readAllPages(String html, List<String> titles) throws IOException {
        Node root = parseHtml(html);
        boolean found = false;
        for (Node ul : all(root, "ul")) {
            if (!ul.attr("class").contains("allpages-chunk")) continue;
            found = true;
            for (Node li : all(ul, "li")) {
                if (li.attr("class").contains("redirect")) continue;
                List<Node> as = all(li, "a");
                if (as.isEmpty()) continue;
                String t = titleFromHref(as.get(0).attr("href"));
                if (t != null) titles.add(t);
            }
        }
        if (!found) for (Node a : all(root, "a")) {          // fallback for other layouts
            String t = titleFromHref(a.attr("href"));
            if (t != null && t.equals(plain(a)) && !a.attr("href").contains("Main_Page")) titles.add(t);
        }
        for (Node a : all(root, "a")) if (plain(a).startsWith("Next page")) return abs(a.attr("href"));
        return null;
    }

    /** Special:ListRedirects: alias -> target. Returns how many pairs were on the page. */
    static int readRedirects(String html, Map<String, List<String>> byTarget) throws IOException {
        int n = 0;
        for (Node li : all(parseHtml(html), "li")) {
            List<Node> as = all(li, "a");
            if (as.size() < 2) continue;
            String from = titleFromHref(as.get(0).attr("href")), to = titleFromHref(as.get(as.size() - 1).attr("href"));
            if (from == null || to == null || from.equalsIgnoreCase(to)) continue;
            List<String> l = byTarget.get(to.toLowerCase(Locale.ROOT));
            if (l == null) { l = new ArrayList<String>(); byTarget.put(to.toLowerCase(Locale.ROOT), l); }
            l.add(from);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ network

    final Path cacheDir, out;
    final int delay, limit;
    final Path imagesDir;
    long lastRequest;
    List<String> fixedTitles;                      // --titles: import only these pages

    WikiImporter(Path cacheDir, Path out, int delay, int limit, Path imagesDir) {
        this.cacheDir = cacheDir; this.out = out; this.delay = delay; this.limit = limit; this.imagesDir = imagesDir;
    }

    byte[] get(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            long wait = lastRequest + delay - System.currentTimeMillis();
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(10000); c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192]; int r;
                    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                    lastRequest = System.currentTimeMillis();
                    return bo.toByteArray();
                }
            } catch (IOException e) {
                last = e; lastRequest = System.currentTimeMillis();
                try { Thread.sleep(1500L * (attempt + 1)); } catch (InterruptedException ignored) { }
            }
        }
        throw last;
    }

    String getText(String url) throws IOException { return new String(get(url), StandardCharsets.UTF_8); }

    static String slug(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }

    String pageHtml(String title) throws IOException {
        Path f = cacheDir.resolve(slug(title) + "." + Integer.toHexString(title.hashCode()) + ".html.gz");
        if (Files.isRegularFile(f)) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(f)); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException ignored) { /* damaged cache file: fetch again */ }
        }
        String url = HOST + "/index.php/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20");
        String html = getText(url);
        Files.createDirectories(cacheDir);
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(f))) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
        return html;
    }

    void cacheImage(Entry e) {
        if (imagesDir == null || e.image.isEmpty()) return;
        Path dest = imagesDir.resolve(slug(e.name) + ".png");
        if (Files.isRegularFile(dest)) return;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(get(e.image)));
            if (src == null) return;
            BufferedImage o = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = o.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double s = Math.min(256.0 / src.getWidth(), 256.0 / src.getHeight());
            int w = (int) Math.round(src.getWidth() * s), h = (int) Math.round(src.getHeight() * s);
            g.drawImage(src, (256 - w) / 2, (256 - h) / 2, w, h, null);
            g.dispose();
            Files.createDirectories(imagesDir);
            ImageIO.write(o, "png", dest.toFile());
        } catch (Exception ex) {
            System.out.println("   (no picture for " + e.name + ": " + ex.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ run

    void runAll() throws Exception {
        List<String> titles = new ArrayList<String>();
        if (fixedTitles != null) {
            titles.addAll(fixedTitles);
            System.out.println("Importing " + titles.size() + " chosen pages.");
        } else {
            System.out.println("Listing pages...");
            String next = HOST + "/index.php?title=Special:AllPages&hideredirects=1";
            Set<String> seen = new HashSet<String>();
            while (next != null && seen.add(next) && seen.size() < 1000) {
                next = readAllPages(getText(next), titles);
                System.out.println("  " + titles.size() + " pages so far");
            }
        }
        System.out.println("Listing redirects (used as search aliases)...");
        Map<String, List<String>> redirects = new HashMap<String, List<String>>();
        for (int offset = 0; offset < 50000; offset += 500) {
            int got = readRedirects(getText(HOST + "/index.php?title=Special:ListRedirects&limit=500&offset=" + offset), redirects);
            if (got < 400) break;
        }
        if (limit > 0 && titles.size() > limit) titles = new ArrayList<String>(titles.subList(0, limit));

        List<Entry> entries = new ArrayList<Entry>();
        List<String> skipped = new ArrayList<String>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < titles.size(); i++) {
            String t = titles.get(i);
            try {
                Entry e = parsePage(t, pageHtml(t));
                if (e == null) { skipped.add(t); }
                else {
                    List<String> al = redirects.get(t.toLowerCase(Locale.ROOT));
                    if (al != null) for (String a : al) if (!a.equalsIgnoreCase(t) && !e.aliases.contains(a)) e.aliases.add(a.replace(';', ','));
                    entries.add(e);
                    cacheImage(e);
                }
            } catch (Exception ex) {
                System.out.println("  failed: " + t + " (" + ex + ")");
                skipped.add(t);
            }
            if ((i + 1) % 25 == 0 || i + 1 == titles.size()) {
                long el = System.currentTimeMillis() - start;
                long eta = (long) ((double) el / (i + 1) * (titles.size() - i - 1) / 60000);
                System.out.println("  " + (i + 1) + "/" + titles.size() + "  imported " + entries.size() + "  (about " + eta + " min left)");
            }
        }
        write(entries, true);
        report(entries, skipped, titles.size());
    }

    void write(List<Entry> entries, boolean articles) throws IOException {
        Collections.sort(entries, new Comparator<Entry>() { public int compare(Entry a, Entry b) { return a.name.compareToIgnoreCase(b.name); } });
        Path parent = out.getParent() == null ? Paths.get(".") : out.getParent();
        Path artDir = parent.resolve(out.getFileName().toString().contains("trial") ? "articles.trial" : "articles");
        if (articles) Files.createDirectories(artDir);
        StringBuilder sb = new StringBuilder("# Imported from Wurmpedia by WikiImporter. One-off copy; the wiki is no longer updated.\n\n");
        for (Entry e : entries) {
            sb.append("@page ").append(e.name).append('\n');
            if (!e.aliases.isEmpty()) sb.append("@alias ").append(String.join("; ", e.aliases)).append('\n');
            if (!e.image.isEmpty()) sb.append("@image ").append(e.image).append('\n');
            if (!e.examine.isEmpty()) sb.append("@examine ").append(e.examine).append('\n');
            if (!e.lead.isEmpty()) sb.append("@lead ").append(e.lead).append('\n');
            if (!e.heads.isEmpty()) sb.append("@heads ").append(e.heads).append('\n');
            for (Section s : e.sections) {
                sb.append("@section ").append(s.title).append('\n');
                for (String l : s.lines) sb.append("- ").append(l).append('\n');
            }
            sb.append('\n');
            if (articles && !e.body.isEmpty())
                Files.write(artDir.resolve(slug(e.name) + "." + Integer.toHexString(e.name.hashCode()) + ".txt"),
                        e.body, StandardCharsets.UTF_8);
        }
        if (parent != null) Files.createDirectories(parent);
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    void report(List<Entry> entries, List<String> skipped, int scanned) throws IOException {
        StringBuilder sb = new StringBuilder();
        int withBox = 0, withBody = 0;
        for (Entry e : entries) { if (!e.sections.isEmpty() || !e.examine.isEmpty()) withBox++; if (!e.body.isEmpty()) withBody++; }
        sb.append("Pages scanned: ").append(scanned).append("\nImported: ").append(entries.size())
          .append("  (with an item box: ").append(withBox).append(", with article text: ").append(withBody).append(")")
          .append("\nSkipped (empty, or failed): ").append(skipped.size()).append("\n\nSection titles found:\n");
        for (Map.Entry<String, Integer> h : HEADERS.entrySet()) sb.append("  ").append(h.getValue()).append("  ").append(h.getKey()).append('\n');
        sb.append("\nFirst skipped pages:\n");
        for (int i = 0; i < Math.min(40, skipped.size()); i++) sb.append("  ").append(skipped.get(i)).append('\n');
        Path rp = (out.getParent() == null ? Paths.get(".") : out.getParent()).resolve("import-report.txt");
        Files.write(rp, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("\nDone. Imported " + entries.size() + " of " + scanned + " pages into " + out + " (article text in the \"articles\" folder next to it)");
        System.out.println("Report: " + rp);
    }

    void single(String title, boolean tree) throws Exception {
        String html = pageHtml(title);
        Node root = parseHtml(html);
        Node tb = infobox(root);
        if (tree) {
            StringBuilder sb = new StringBuilder();
            if (tb != null) tree(tb, "", sb); else sb.append("(no item box on this page)\n");
            System.out.println("--- infobox structure ---\n" + sb);
        }
        Entry e = parsePage(title, html);
        System.out.println("--- parsed ---");
        if (e == null) { System.out.println("(nothing parsed; the page is saved in " + cacheDir + ")"); return; }
        List<Entry> one = new ArrayList<Entry>(); one.add(e);
        Path tmp = Files.createTempFile("wi", ".txt");
        new WikiImporter(cacheDir, tmp, delay, limit, null).write(one, false);
        System.out.println(new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8));
        System.out.println("--- article text (" + e.body.size() + " lines; first 40 shown) ---");
        for (int i = 0; i < Math.min(40, e.body.size()); i++) {
            String l = e.body.get(i);
            System.out.println(l.length() > 150 ? l.substring(0, 150) + "..." : l);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) if (args[i].startsWith("--")) {
            String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            o.put(args[i].substring(2), v);
        }
        WikiImporter imp = new WikiImporter(Paths.get(o.getOrDefault("cache", "wiki-cache")), Paths.get(o.getOrDefault("out", "pages.txt")),
                Integer.parseInt(o.getOrDefault("delay", "250")), Integer.parseInt(o.getOrDefault("limit", "0")),
                o.containsKey("images") ? Paths.get(o.get("images")) : null);
        if (o.containsKey("titles")) {
            imp.fixedTitles = new ArrayList<String>();
            for (String t : o.get("titles").split(",")) if (!t.trim().isEmpty()) imp.fixedTitles.add(t.trim());
        }
        if (o.containsKey("only")) imp.single(o.get("only"), o.containsKey("tree")); else imp.runAll();
    }
}
