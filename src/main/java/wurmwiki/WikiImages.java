package wurmwiki;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Fetches each entry's picture once (in the background), fits it onto a 256x256 transparent PNG
 * and keeps it in mods/wikihelper/images so it works offline afterwards.
 */
public final class WikiImages {

    public static final int NONE = 0, LOADING = 1, READY = 2, FAILED = 3;

    public static volatile boolean enabled = true;

    private static final Path DIR = Paths.get("mods", "wikihelper", "images");
    private static final Set<String> started = ConcurrentHashMap.newKeySet();
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();

    private WikiImages() { }

    public static File fileFor(WikiDatabase.Page p) {
        return DIR.resolve(p.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_") + ".png").toFile();
    }

    public static int state(WikiDatabase.Page p) {
        if (p == null || p.image.isEmpty()) return NONE;
        if (fileFor(p).isFile()) return READY;
        if (failed.contains(p.name)) return FAILED;
        return enabled && p.image.startsWith("http") ? LOADING : FAILED;
    }

    /** Starts a background download if the picture is not cached yet. Safe to call every time a page opens. */
    public static void request(final WikiDatabase.Page p) {
        if (!enabled || p == null || p.image.isEmpty() || !p.image.startsWith("http")) return;
        if (fileFor(p).isFile() || !started.add(p.name)) return;
        Thread t = new Thread(new Runnable() { public void run() { download(p); } }, "WurmWiki-image");
        t.setDaemon(true);
        t.start();
    }

    private static void download(WikiDatabase.Page p) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(p.image).openConnection();
            c.setRequestProperty("User-Agent", "WurmWikiMod/1.0 (Wurm Unlimited client mod)");
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            BufferedImage src;
            try (InputStream in = c.getInputStream()) { src = ImageIO.read(in); }
            if (src == null) throw new IllegalStateException("unreadable image");

            BufferedImage out = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double s = Math.min(256.0 / src.getWidth(), 256.0 / src.getHeight());
            int w = (int) Math.round(src.getWidth() * s), h = (int) Math.round(src.getHeight() * s);
            g.drawImage(src, (256 - w) / 2, (256 - h) / 2, w, h, null);
            g.dispose();

            Files.createDirectories(DIR);
            File dest = fileFor(p);
            File tmp = new File(dest.getPath() + ".tmp");
            ImageIO.write(out, "png", tmp);
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            WikiMod.LOG.info("Wiki: cached image for " + p.name);
        } catch (Throwable t) {
            failed.add(p.name);
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not fetch image for " + p.name + " (" + t + ")");
        }
    }
}
