# cards-framework

Shared engine for the local-multiplayer card game apps
[Scopa](https://github.com/focagroup/scopa-cards),
[Jass](https://github.com/focagroup/jass-cards) and
[Tablić](https://github.com/focagroup/tablic-cards).

Each game is an Android app (built offline, no Gradle) that runs a small game server on the
phone and shows a web UI in a WebView. Other players join from a browser on the same Wi-Fi or
from the app on a second phone over Bluetooth. The apps never contact the internet.

Everything that does not depend on the rules of a particular game lives here:

| Part | Classes | What it does |
|---|---|---|
| `core/` (pure Java 8) | `Room` | Lobby, seats (local / remote / bot), joining, long-poll state, access control, bot loop, outer shape of the state JSON. Games subclass it. |
| | `WebServer` | Tiny HTTP server for the local network: serves the web client and the JSON API. Loopback requests get host rights. |
| | `Relay` | Carries API calls between a Bluetooth guest and the host over one stream. |
| | `Json`, `GameInfo` | Minimal JSON writer; a game's identity (id, ASCII name, display title). |
| `android/` | `CardsActivity` | The whole Android app: WebView locked to the local server, Bluetooth hosting and joining, native dialogs. A game's `MainActivity` extends it. |
| | `Bluetooth` | RFCOMM host listener and guest connector. |
| `desktop/` | `DesktopMain`, `RelayTest`, `FileAssets` | Run a game's server on a PC, and test the Bluetooth relay over TCP. |
| `build.sh` | | Offline APK build plus desktop, test and relay-test targets for any game. |

The web UI (`app/assets/web/`) and card skins still live in each game.

## Using it in a game

The framework is a git submodule at `framework/` in each game repo:

```bash
git clone --recursive https://github.com/focagroup/scopa-cards
# or, in an existing clone:
git submodule update --init
```

A game provides:

1. **`game.conf`**, which `build.sh` reads:
   ```bash
   GAME=scopa          # Java package app.scopa, build/scopa.apk
   KS_NAME=Scopa       # CN of a key made by "./build.sh newkey"
   ```
2. **`build.sh`**, a two-liner that runs `exec framework/build.sh "$@"` from the game folder.
3. **`app/core/app/<game>/core/Room.java`**, which extends `app.cards.core.Room`. It holds the
   game's `GameInfo INFO`, its config and rules engine, and implements the hooks:
   - lifecycle: `hasGame`, `players`, `configureGame`, `newGame`, `nextGameRound`, `clearGame`
   - moves: `handles(action)` names the game's API action(s) (e.g. `play`, `act`), and
     `play(seat, action, params)` applies one. The framework has already checked that the
     caller controls the seat.
   - bots: `botTurn`, `botPlay`, plus the optional `botExtra`, `botDelayHint` and `botErrorPauseMs`
   - state: `configView`, `phase`, `gameView(mySeats)`
4. **`app/src/app/<game>/MainActivity.java`**, which extends `CardsActivity` and supplies `info()`,
   `createRoom()`, `bluetoothService()` (a UUID unique to the game), `pageColor()`, `frameColor()`
   and `exitQuestion()`.
5. **`desktop/app/<game>/desktop/{DesktopMain,RelayTest}.java`**: one-line mains that call
   `app.cards.desktop.DesktopMain.run(new Room(), args)` and `RelayTest.run(...)`, plus the
   game's own `SimTest`.

`GameInfo` derives the per-game names: preferences `<id>`, threads `<id>-http` and `<id>-bots`,
relay handshake `<id>-relay`, JavaScript bridge `window.<Name>App` and Bluetooth service `<Name>`.
Two different games therefore never connect to each other.

## API (shared by all games)

`GET /api/state?token=&since=` is a long poll (20 s). It returns `{v, isHost, gameId, mySeats, config,
seats[], addresses?, port?, phase: lobby|playing|roundEnd|gameOver, game?}`.

`POST /api/config | join | leave | start | next | end | seat` are the lobby and host actions.
Any other `POST /api/<action>` goes to the game's Room if `handles(action)` is true; otherwise
the server answers 404.

## Build

Requires JDK 17+ and the Android SDK (`platforms/android-35`, `build-tools/35.0.0`) in
`~/Android/Sdk`. From a game folder:

```bash
./build.sh            # -> build/<game>.apk (min Android 7.0 / API 24)
./build.sh test       # the game's rule checks and bot simulations
./build.sh desktop    # run the server on this PC (http://localhost:8080)
./build.sh relaytest  # Bluetooth relay checks, with TCP standing in for Bluetooth
```

## Signing

Signing keys and their passwords are never in a repository. The APK build signs with

```
$CARDS_KEYS/<game>.jks    # PKCS12 keystore, key alias <game>
$CARDS_KEYS/<game>.pass   # its password on the first line
```

`CARDS_KEYS` defaults to `~/claude/.keys/cards`. Keep the folder `chmod 700` and the files
`chmod 600` (the build warns otherwise), and back them up: Android only installs an update
signed with the same key as the installed app. The password goes to `apksigner` as a file
name, so it never appears on a command line.

If the key is missing, the build stops instead of making a new one. Only for a brand-new
game, `./build.sh newkey` creates the key and a random password there, and it never
overwrites an existing key.

## Updating the framework in a game

```bash
cd framework && git pull && cd ..
./build.sh test && ./build.sh relaytest
git commit -am "Update cards-framework"
```

## License

AGPL-3.0, see `LICENSE`.
