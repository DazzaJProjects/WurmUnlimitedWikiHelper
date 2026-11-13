package wurmwiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local copy of the wiki. File format (pages.txt):
 *
 *   @page Bulk storage bin
 *   @alias bsb; bulk bin
 *   @image https://.../Bsb.jpg
 *   @examine A storage bin made from planks...
 *   @section Total materials
 *   - 25x [[Plank|planks]]
 *   - 4x [[Large nails|large nails]]
 *
 * [[Target|shown text]] (or [[Target]]) is a link to another page in this file.
 */
public class WikiDatabase {

    /** A run of text, optionally a link to another page. */
    public static final class Seg {
        public final String text, target;
        public Seg(String text, String target) { this.text = text; this.target = target; }
    }

    public static final class Section {
        public final String title;
        public final List<String> lines = new ArrayList<String>();
        Section(String title) { this.title = title; }
    }

    public static final class Page {
        public final String name, image, examine, lead;
        public final String[] aliases;
        public final List<Section> sections;
        private final List<String> inlineBody;       // only for entries written directly in pages.txt / custom.txt
        final String[] keys;
        final String haystack;

        Page(String name, List<String> aliases, String image, String examine, String lead, String heads,
             List<Section> sections, List<String> inlineBody) {
            this.name = name;
            this.aliases = aliases.toArray(new String[0]);
            this.image = image;
            this.examine = examine;
            this.lead = lead;
            this.sections = sections;
            this.inlineBody = inlineBody;
            Set<String> k = new LinkedHashSet<String>();
            addKeys(k, name);
            for (String a : aliases) addKeys(k, a);
            this.keys = k.toArray(new String[0]);
            StringBuilder sb = new StringBuilder();
            for (String s : keys) sb.append(s).append('\n');
            sb.append(examine.toLowerCase(Locale.ROOT)).append('\n');
            sb.append(lead.toLowerCase(Locale.ROOT)).append('\n');
            sb.append(heads.toLowerCase(Locale.ROOT)).append('\n');
            for (Section s : sections) {
                sb.append(s.title.toLowerCase(Locale.ROOT)).append('\n');
                for (String l : s.lines) sb.append(strip(l).toLowerCase(Locale.ROOT)).append('\n');
            }
            this.haystack = sb.toString();
        }

        /**
         * The article text as tagged lines: H2/H3 headings, P paragraphs, L/L2 bullets, N/N2 numbered items,
         * TH/TR table rows (cells separated by tabs). Read from the articles folder when the page is opened.
         */
        public List<String> body() { return inlineBody != null ? inlineBody : readArticle(name); }
    }

    private static final class Builder {
        String name, image = "", examine = "", lead = "", heads = "";
        final List<String> aliases = new ArrayList<String>();
        final List<Section> sections = new ArrayList<Section>();
        List<String> body;
        boolean inBody;
        Section section;
        Builder(String name) { this.name = name; }
        Page build() { return new Page(name, aliases, image, examine, lead, heads, sections, body); }
    }

    // ------------------------------------------------------------------ article files

    private static volatile Path articlesDir = Paths.get("mods", "wikihelper", "articles");

    public static void setArticlesDir(Path dir) { articlesDir = dir; }

    public static String slug(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }

    private static final Map<String, List<String>> ARTICLES = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) { return size() > 12; }
            });

    static List<String> readArticle(String name) {
        List<String> hit = ARTICLES.get(name);
        if (hit != null) return hit;
        List<String> lines = new ArrayList<String>();
        try {
            Path f = articlesDir.resolve(slug(name) + "." + Integer.toHexString(name.hashCode()) + ".txt");
            if (Files.isRegularFile(f))
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) lines.add(l);
        } catch (IOException ignored) { }
        ARTICLES.put(name, lines);
        return lines;
    }

    private volatile List<Page> pages = Collections.emptyList();
    private volatile Map<String, Page> byKey = Collections.emptyMap();

    /**
     * Loads the first file (the big imported database) and then any later files (your own edits).
     * A page in a later file replaces a page of the same name from an earlier one. Missing later files are fine.
     */
    public int load(Path... files) throws IOException {
        Map<String, Page> merged = new LinkedHashMap<String, Page>();
        for (int i = 0; i < files.length; i++) {
            if (i > 0 && !Files.isRegularFile(files[i])) continue;
            for (Page p : parse(files[i])) merged.put(p.name.toLowerCase(Locale.ROOT), p);
        }
        List<Page> loaded = new ArrayList<Page>(merged.values());
        Collections.sort(loaded, new Comparator<Page>() {
            public int compare(Page a, Page c) { return a.name.compareToIgnoreCase(c.name); }
        });
        Map<String, Page> map = new HashMap<String, Page>();
        for (Page p : loaded) {                       // real names win over aliases
            Set<String> k = new LinkedHashSet<String>();
            addKeys(k, p.name);
            for (String s : k) if (!map.containsKey(s)) map.put(s, p);
        }
        for (Page p : loaded) for (String s : p.keys) if (!map.containsKey(s)) map.put(s, p);
        pages = loaded;
        byKey = map;
        return loaded.size();
    }

    private static List<Page> parse(Path file) throws IOException {
        List<Page> loaded = new ArrayList<Page>();
        Builder b = null;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("@page ")) {
                if (b != null) loaded.add(b.build());
                b = new Builder(t.substring(6).trim());
            } else if (b == null) {
                continue;
            } else if (t.startsWith("@")) {
                b.inBody = false;
                if (t.startsWith("@alias ")) {
                    for (String a : t.substring(7).split(";")) if (!a.trim().isEmpty()) b.aliases.add(a.trim());
                } else if (t.startsWith("@image ")) b.image = t.substring(7).trim();
                else if (t.startsWith("@examine ")) b.examine = t.substring(9).trim();
                else if (t.startsWith("@lead ")) b.lead = t.substring(6).trim();
                else if (t.startsWith("@heads ")) b.heads = t.substring(7).trim();
                else if (t.startsWith("@section ")) { b.section = new Section(t.substring(9).trim()); b.sections.add(b.section); }
                else if (t.equals("@body")) { b.body = new ArrayList<String>(); b.inBody = true; }
            } else if (b.inBody) {
                b.body.add(raw.replaceAll("^\\s+", ""));
            } else if (t.startsWith("- ") && b.section != null) {
                b.section.lines.add(t.substring(2).trim());
            }
        }
        if (b != null) loaded.add(b.build());
        return loaded;
    }

    public int size() { return pages.size(); }

    public List<Page> all() { return pages; }

    /** Exact lookup by page name or alias (case-insensitive, tolerant of plural/singular and underscores). */
    public Page resolve(String target) {
        if (target == null) return null;
        return byKey.get(target.replace('_', ' ').trim().toLowerCase(Locale.ROOT));
    }

    /** Every search word must appear somewhere on the page. Best name matches come first. */
    public List<Page> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return Collections.emptyList();
        String[] terms = q.split("\\s+");
        final List<Page> hits = new ArrayList<Page>();
        final Map<Page, Integer> score = new HashMap<Page, Integer>();
        for (Page p : pages) {
            boolean all = true;
            for (String t : terms) if (!p.haystack.contains(t)) { all = false; break; }
            if (!all) continue;
            int s = 3;
            for (String k : p.keys) {
                if (k.equals(q)) { s = 0; break; }
                if (k.startsWith(q)) s = Math.min(s, 1);
                else if (k.contains(q)) s = Math.min(s, 2);
            }
            hits.add(p);
            score.put(p, s);
        }
        Collections.sort(hits, new Comparator<Page>() {
            public int compare(Page a, Page b) {
                int c = score.get(a) - score.get(b);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        return hits;
    }

    /** Turns an in-game title ("Bulk storage bin, ql 41.2") into a plain lower-case name. */
    public static String normalise(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(Locale.ROOT);
        int comma = t.indexOf(',');
        if (comma >= 0) t = t.substring(0, comma);
        t = t.replaceAll("\\([^)]*\\)", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Finds the page that best fits an in-game item or object title, or null. */
    public Page find(String title) {
        String n = normalise(title);
        if (n.length() < 3) return null;
        Page exact = resolve(n);
        if (exact != null) return exact;
        Page best = null;
        int bestLen = -1;
        for (Page p : pages) {
            for (String k : p.keys) {
                boolean hit = containsWord(n, k) || (n.length() >= 5 && k.contains(n));
                if (hit && k.length() > bestLen) { best = p; bestLen = k.length(); }
            }
        }
        return best;
    }

    private static boolean containsWord(String text, String key) {
        if (key.length() < 2) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(key, from);
            if (i < 0) return false;
            int e = i + key.length();
            boolean left = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            boolean right = e == text.length() || !Character.isLetterOrDigit(text.charAt(e));
            if (left && right) return true;
            from = i + 1;
        }
    }

    private static void addKeys(Set<String> out, String raw) {
        String k = raw.toLowerCase(Locale.ROOT).trim();
        if (k.isEmpty()) return;
        out.add(k);
        if (k.endsWith("s") && k.length() > 3) out.add(k.substring(0, k.length() - 1));
        else out.add(k + "s");
    }

    // ------------------------------------------------------------------ link markup

    public static List<Seg> segments(String line) {
        List<Seg> out = new ArrayList<Seg>();
        int i = 0;
        while (i < line.length()) {
            int open = line.indexOf("[[", i);
            int close = open < 0 ? -1 : line.indexOf("]]", open + 2);
            if (open < 0 || close < 0) { out.add(new Seg(line.substring(i), null)); break; }
            if (open > i) out.add(new Seg(line.substring(i, open), null));
            String inner = line.substring(open + 2, close);
            int bar = inner.indexOf('|');
            String target = (bar >= 0 ? inner.substring(0, bar) : inner).trim();
            String shown = bar >= 0 ? inner.substring(bar + 1) : inner;
            out.add(new Seg(shown, target));
            i = close + 2;
        }
        return out;
    }

    public static String strip(String line) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : segments(line)) sb.append(s.text);
        return sb.toString();
    }
}
