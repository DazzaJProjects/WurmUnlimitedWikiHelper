package wurmwiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Remembers which entries the player opens: how often, and how recently. Saved to history.txt. */
public class WikiHistory {

    private final Path file;
    private final Map<String, long[]> data = new HashMap<String, long[]>(); // name -> {count, lastTick}
    private long tick;

    public WikiHistory(Path file) { this.file = file; }

    public synchronized void load() {
        data.clear();
        try {
            if (!Files.isRegularFile(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|");
                if (p.length < 3) continue;
                try {
                    long last = Long.parseLong(p[2].trim());
                    data.put(p[0].trim(), new long[]{Long.parseLong(p[1].trim()), last});
                    tick = Math.max(tick, last);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException e) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not read history", e);
        }
    }

    public synchronized void record(String name) {
        long[] e = data.get(name);
        if (e == null) { e = new long[]{0, 0}; data.put(name, e); }
        e[0]++;
        tick = Math.max(System.currentTimeMillis(), tick + 1);
        e[1] = tick;
        save();
    }

    public synchronized int count(String name) {
        long[] e = data.get(name);
        return e == null ? 0 : (int) e[0];
    }

    public synchronized List<String> recent(int n) { return top(n, false); }

    public synchronized List<String> popular(int n) { return top(n, true); }

    private List<String> top(int n, final boolean byCount) {
        List<String> names = new ArrayList<String>(data.keySet());
        Collections.sort(names, new Comparator<String>() {
            public int compare(String a, String b) {
                long[] x = data.get(a), y = data.get(b);
                if (byCount && x[0] != y[0]) return x[0] > y[0] ? -1 : 1;
                return x[1] > y[1] ? -1 : (x[1] < y[1] ? 1 : 0);
            }
        });
        return new ArrayList<String>(names.subList(0, Math.min(n, names.size())));
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<String>();
            for (Map.Entry<String, long[]> e : data.entrySet())
                lines.add(e.getKey() + "|" + e.getValue()[0] + "|" + e.getValue()[1]);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not save history", e);
        }
    }
}
