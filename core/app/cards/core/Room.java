package app.cards.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A single game room: lobby configuration, seats, the running game and the bot driver.
 * All public methods are synchronized; state changes bump `version` and wake long-polls.
 *
 * The framework owns everything that is the same for every card game (seats, joining,
 * long polling, access control, the bot loop and the outer shape of the state JSON).
 * A game subclass supplies its configuration, its rules engine and the "game" part of the
 * state through the abstract hooks below. All hooks are called with the Room lock held.
 */
public abstract class Room {
    public static final int LOCAL = 0, REMOTE = 1, BOT = 2;
    private static final String[] TYPE_NAMES = {"local", "remote", "bot"};
    private static final long ONLINE_MS = 45000;

    public static final class Seat {
        public int type;
        public String name = "";
        public String token;      // remote seats: owning client token
        public int botLevel = 1;
        public long lastSeen;
    }

    public final GameInfo info;
    protected final Random rng = new Random();
    protected final Seat[] seats;
    private final long botDelayMs;
    private long version = 1;
    private long lastChange = System.currentTimeMillis();
    private int gameId = 0;
    private Thread bots;

    /**
     * @param maxSeats   number of seats shown in the lobby (the game uses the first {@link #players()})
     * @param botDelayMs pause before a bot acts after the last change
     */
    protected Room(GameInfo info, int maxSeats, long botDelayMs) {
        this.info = info;
        this.botDelayMs = botDelayMs;
        seats = new Seat[maxSeats];
        for (int i = 0; i < maxSeats; i++) {
            seats[i] = new Seat();
            seats[i].type = i == 0 ? LOCAL : BOT;
        }
    }

    // ------------------------------------------------------------------ game hooks

    /** True while a game (or match) is running, including between rounds and after game over. */
    protected abstract boolean hasGame();

    /** Seats in play: the running game's player count, otherwise the lobby setting. */
    protected abstract int players();

    /** Applies the game-specific lobby settings from a "config" call (seat settings are done here). */
    protected abstract void configureGame(Map<String, String> p);

    /** Starts a new game with the current lobby settings. */
    protected abstract void newGame();

    protected abstract void nextGameRound();

    /** Drops the running game (back to the lobby). */
    protected abstract void clearGame();

    /** The "config" object of the state: the running game's settings, otherwise the lobby's. */
    protected abstract Object configView();

    /** "playing", "roundEnd" or "gameOver". */
    protected abstract String phase();

    /** The "game" object of the state, as seen by the given seats. */
    protected abstract Map<String, Object> gameView(List<Integer> mine);

    /** API action names (besides the lobby ones) that {@link #play} understands, e.g. "play". */
    public abstract boolean handles(String action);

    /** A move by a seat the caller controls. Returns an error key or null. */
    protected abstract String play(int seat, String action, Map<String, String> p);

    /** The seat a bot should act for now, or -1. Only seats of type BOT are then played. */
    protected abstract int botTurn();

    /** Lets the bot on `seat` act. */
    protected abstract void botPlay(int seat, int level);

    /** Out-of-turn bot actions; return true if something changed. */
    protected boolean botExtra() { return false; }

    /** Extra pause (ms) before the next bot move, e.g. to let people see a finished trick. */
    protected long botDelayHint() { return 0; }

    /** Pause (ms) after a bot move threw, so a broken state cannot spin the bot thread. */
    protected long botErrorPauseMs() { return 0; }

    // ------------------------------------------------------------------ core

    protected final void changed() {
        version++;
        lastChange = System.currentTimeMillis();
        notifyAll();
    }

    public final Seat seat(int i) { return seats[i]; }

    // ------------------------------------------------------------------ long poll

    public synchronized void awaitChange(long since, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (version <= since) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return;
            try { wait(left); } catch (InterruptedException e) { return; }
        }
    }

    // ------------------------------------------------------------------ bots

    /** The bot thread starts with the first game, once the subclass is fully constructed. */
    private void startBots() {
        if (bots != null) return;
        bots = new Thread(new Runnable() {
            @Override public void run() { botLoop(); }
        }, info.id + "-bots");
        bots.setDaemon(true);
        bots.start();
    }

    private void botLoop() {
        while (true) {
            synchronized (this) {
                try {
                    if (hasGame() && botExtra()) {
                        changed();
                        continue;
                    }
                    int turn = hasGame() ? botTurn() : -1;
                    if (turn >= 0 && seats[turn].type == BOT) {
                        long due = lastChange + botDelayMs + botDelayHint();
                        long now = System.currentTimeMillis();
                        if (now >= due) {
                            botPlay(turn, seats[turn].botLevel);
                            changed();
                        } else {
                            wait(due - now);
                        }
                    } else {
                        wait(5000);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    long pause = botErrorPauseMs();
                    if (pause > 0) {
                        try { wait(pause); } catch (InterruptedException ie) { return; }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ access control

    protected final boolean controls(int seat, String token, boolean isHost) {
        if (seat < 0 || seat >= players()) return false;
        Seat s = seats[seat];
        if (s.type == LOCAL) return isHost;
        if (s.type == REMOTE) return token != null && token.equals(s.token);
        return false;
    }

    private void touch(String token) {
        if (token == null) return;
        long now = System.currentTimeMillis();
        for (Seat s : seats) {
            if (s.type == REMOTE && token.equals(s.token)) {
                boolean wasOffline = now - s.lastSeen > ONLINE_MS;
                s.lastSeen = now;
                if (wasOffline) changed();
            }
        }
    }

    // ------------------------------------------------------------------ lobby actions

    public synchronized String configure(boolean isHost, Map<String, String> p) {
        if (!isHost) return "host_only";
        if (hasGame()) return "game_running";
        configureGame(p);
        for (int i = 0; i < seats.length; i++) {
            Seat s = seats[i];
            String type = p.get("seat" + i + "_type");
            if (type != null) {
                int nt = indexOf(TYPE_NAMES, type);
                if (nt >= 0 && nt != s.type) {
                    s.type = nt;
                    s.token = null;
                    s.name = "";
                }
            }
            String name = p.get("seat" + i + "_name");
            if (name != null && s.type != REMOTE) s.name = cleanName(name);
            String lvl = p.get("seat" + i + "_bot");
            if (lvl != null) s.botLevel = clamp(parseInt(lvl, 1), 0, 2);
        }
        changed();
        return null;
    }

    public synchronized String join(String token, int seat, String name) {
        if (token == null || token.length() < 8) return "bad_token";
        if (hasGame()) return "game_running";
        if (seat < 0 || seat >= players()) return "bad_seat";
        Seat s = seats[seat];
        if (s.type != REMOTE) return "bad_seat";
        if (s.token != null && !s.token.equals(token)) return "seat_taken";
        for (Seat o : seats) if (o != s && token.equals(o.token)) { o.token = null; o.name = ""; }
        s.token = token;
        s.name = cleanName(name);
        s.lastSeen = System.currentTimeMillis();
        changed();
        return null;
    }

    public synchronized String leave(String token) {
        if (token == null) return null;
        for (Seat s : seats) {
            if (token.equals(s.token) && !hasGame()) { s.token = null; s.name = ""; }
        }
        changed();
        return null;
    }

    public synchronized String start(boolean isHost) {
        if (!isHost) return "host_only";
        if (hasGame()) return "game_running";
        for (int i = 0; i < players(); i++)
            if (seats[i].type == REMOTE && seats[i].token == null) return "seats_open";
        newGame();
        gameId++;
        startBots();
        changed();
        return null;
    }

    public synchronized String nextRound(boolean isHost) {
        if (!isHost) return "host_only";
        if (!hasGame()) return "no_game";
        nextGameRound();
        changed();
        return null;
    }

    public synchronized String endGame(boolean isHost) {
        if (!isHost) return "host_only";
        clearGame();
        changed();
        return null;
    }

    /** Mid-game: hand a seat (e.g. a disconnected remote player) to a bot, or back to the host. */
    public synchronized String setSeatType(boolean isHost, int seat, String type) {
        if (!isHost) return "host_only";
        if (seat < 0 || seat >= players()) return "bad_seat";
        int nt = indexOf(TYPE_NAMES, type);
        if (nt < 0 || nt == REMOTE) return "bad_type";
        seats[seat].type = nt;
        seats[seat].token = null;
        changed();
        return null;
    }

    /** A game move (see {@link #handles}) from whoever controls `seat`. */
    public synchronized String gameAction(String token, boolean isHost, int seat, String action, Map<String, String> p) {
        if (!hasGame()) return "no_game";
        if (!controls(seat, token, isHost)) return "not_your_seat";
        String err = play(seat, action, p);
        if (err == null) changed();
        return err;
    }

    // ------------------------------------------------------------------ state view

    public synchronized String state(String token, boolean isHost, List<String> addresses, int port) {
        touch(token);
        long now = System.currentTimeMillis();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("v", version);
        o.put("isHost", isHost);
        o.put("gameId", gameId);

        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < players(); i++) if (controls(i, token, isHost)) mine.add(i);
        o.put("mySeats", mine);
        o.put("config", configView());

        List<Object> seatList = new ArrayList<>();
        for (Seat s : seats) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", TYPE_NAMES[s.type]);
            m.put("name", s.name);
            m.put("bot", s.botLevel);
            m.put("taken", s.type != REMOTE || s.token != null);
            m.put("online", s.type != REMOTE || (s.token != null && now - s.lastSeen < ONLINE_MS));
            m.put("you", token != null && token.equals(s.token));
            seatList.add(m);
        }
        o.put("seats", seatList);

        if (isHost) {
            o.put("addresses", addresses);
            o.put("port", port);
        }

        if (!hasGame()) {
            o.put("phase", "lobby");
            return Json.write(o);
        }
        o.put("phase", phase());
        o.put("game", gameView(mine));
        return Json.write(o);
    }

    // ------------------------------------------------------------------ helpers

    protected static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 16; i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20 && ch != '<' && ch != '>') b.append(ch);
        }
        return b.toString().trim();
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    public static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** "1,4,7" -> {1, 4, 7}; empty or missing -> {}. */
    public static int[] parseInts(String s) {
        if (s == null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parseInt(parts[i], -1);
        return out;
    }

    protected static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
