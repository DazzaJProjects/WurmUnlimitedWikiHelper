package com.wurmonline.client.resources.textures;

import com.wurmonline.client.resources.WikiFileResourceUrl;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads a PNG from disk as a game texture. Returns null if it cannot be loaded. */
public final class WikiTextureFactory {
    private static final Logger LOG = Logger.getLogger("WurmWiki");

    private WikiTextureFactory() { }

    public static ResourceTexture load(File file) {
        try {
            WikiFileResourceUrl url = new WikiFileResourceUrl(file.getPath());
            if (!url.exists()) return null;
            return ResourceTextureLoader.getInternalTexture(url, TextureLoader.Filter.LINEAR, false, false, false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Wiki: could not load texture " + file, t);
            return null;
        }
    }
}
