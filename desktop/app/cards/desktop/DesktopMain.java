package app.cards.desktop;

import app.cards.core.Room;
import app.cards.core.WebServer;

/**
 * Runs a game's server on a desktop JVM for testing. Each game has a two-line main that calls
 * {@code DesktopMain.run(new Room(), args)} with args: [webDir] [port] [skinsDir].
 */
public final class DesktopMain {
    private DesktopMain() {}

    public static void run(Room room, String[] args) throws Exception {
        String web = args.length > 0 ? args[0] : "app/assets/web";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        String skins = args.length > 2 ? args[2] : "skins";
        WebServer ws = new WebServer(room.info, room, new FileAssets(web, skins));
        int p = ws.start(port);
        System.out.println(room.info.title + " server on port " + p + "  addresses: " + WebServer.localAddresses());
        Thread.currentThread().join();
    }
}
