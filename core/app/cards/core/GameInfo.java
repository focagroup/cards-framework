package app.cards.core;

/**
 * Identity of one card game built on the framework. Everything that used to be spelled out
 * per app (thread names, the Bluetooth relay handshake, the JavaScript bridge, preferences)
 * is derived from these three names, so two different games never talk to each other.
 */
public final class GameInfo {
    /** Lower-case id, e.g. "tablic": Java package suffix, preferences, thread names, relay magic. */
    public final String id;
    /** ASCII name, e.g. "Tablic": JavaScript bridge ("TablicApp") and Bluetooth service name. */
    public final String name;
    /** Display title, e.g. "Tablić". */
    public final String title;

    public GameInfo(String id, String name, String title) {
        this.id = id;
        this.name = name;
        this.title = title;
    }

    /** Handshake string of the Bluetooth relay, e.g. "tablic-relay". */
    public String relayMagic() { return id + "-relay"; }

    /** Name of the JavaScript interface the web client finds on `window`, e.g. "TablicApp". */
    public String bridgeName() { return name + "App"; }
}
