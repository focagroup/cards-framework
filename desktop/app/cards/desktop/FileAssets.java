package app.cards.desktop;

import java.io.File;
import java.nio.file.Files;

import app.cards.core.WebServer;

/** Serves the web client from a folder and "skins/..." from the skins folder (desktop runs). */
public final class FileAssets implements WebServer.AssetSource {
    private final File web;
    private final File skins;

    public FileAssets(String webDir, String skinsDir) throws java.io.IOException {
        web = new File(webDir).getCanonicalFile();
        skins = new File(skinsDir).getCanonicalFile();
    }

    @Override public byte[] read(String path) {
        try {
            File root = web;
            if (path.startsWith("skins/")) { root = skins; path = path.substring(6); }
            File f = new File(root, path).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + File.separator) || !f.isFile()) return null;
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return null;
        }
    }
}
