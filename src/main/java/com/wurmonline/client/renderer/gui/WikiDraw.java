package com.wurmonline.client.renderer.gui;

import com.wurmonline.client.resources.textures.ResourceTexture;
import com.wurmonline.client.resources.textures.WikiTextureFactory;
import wurmwiki.WikiDatabase;
import wurmwiki.WikiImages;
import wurmwiki.WikiMod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/** Textures used by the Wiki window: a white pixel (tinted to draw coloured boxes) and the entry pictures. */
final class WikiDraw {
    private static ResourceTexture pixel;
    private static boolean pixelTried;
    private static final Map<String, ResourceTexture> IMAGES = new HashMap<String, ResourceTexture>();

    private WikiDraw() { }

    static synchronized ResourceTexture pixel() {
        if (pixel == null && !pixelTried) {
            pixelTried = true;
            try {
                File f = new File("mods/wikihelper/assets/pixel.png");
                if (!f.isFile()) {
                    f.getParentFile().mkdirs();
                    BufferedImage im = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) im.setRGB(x, y, 0xFFFFFFFF);
                    ImageIO.write(im, "png", f);
                }
                pixel = WikiTextureFactory.load(f);
            } catch (Throwable t) {
                WikiMod.LOG.log(Level.WARNING, "Wiki: could not create the colour texture", t);
            }
        }
        return pixel;
    }

    /** The entry's picture once it has been downloaded, otherwise null. */
    static synchronized ResourceTexture image(WikiDatabase.Page page) {
        ResourceTexture t = IMAGES.get(page.name);
        if (t == null && WikiImages.state(page) == WikiImages.READY) {
            t = WikiTextureFactory.load(WikiImages.fileFor(page));
            if (t != null) IMAGES.put(page.name, t);
        }
        return t;
    }
}
