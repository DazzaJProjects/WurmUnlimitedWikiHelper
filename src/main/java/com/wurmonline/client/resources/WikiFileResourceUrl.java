package com.wurmonline.client.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/** Lets the game's texture loader read a plain file from disk (the cached wiki pictures). */
public final class WikiFileResourceUrl extends ResourceUrl {
    private final Path file;

    public WikiFileResourceUrl(String filePath) { this(Paths.get(filePath)); }

    private WikiFileResourceUrl(Path file) {
        super(file.toString());
        this.file = file.toAbsolutePath().normalize();
    }

    @Override public ResourceUrl derive(String relative) {
        Path parent = file.getParent();
        return new WikiFileResourceUrl(parent == null ? Paths.get(relative) : parent.resolve(relative));
    }

    @Override public ResourceUrl changeFilePath(String filePath) { return new WikiFileResourceUrl(filePath); }

    @Override public InputStream openStream() throws IOException { return Files.newInputStream(file); }

    @Override public boolean exists() { return Files.isRegularFile(file); }

    @Override public String getFilePath() { return file.toString(); }

    @Override public Map<String, String> getOverrides() { return Collections.emptyMap(); }

    @Override long getSize() {
        try { return Files.size(file); } catch (IOException ignored) { return 0; }
    }

    @Override public boolean equals(Object other) {
        return other instanceof WikiFileResourceUrl && file.equals(((WikiFileResourceUrl) other).file);
    }

    @Override public String toString() { return "wiki-file:" + file; }
}
