# SmartRejoin for Velocity

![Java Version](https://img.shields.io/badge/Java-21+-blue?style=for-the-badge&logo=openjdk)
![Velocity Version](https://img.shields.io/badge/Velocity-3.4.0+-orange?style=for-the-badge)

**SmartRejoin** is a modern alternative to older reconnect plugins, built with a highly customizable rule-based engine.

---

## Features

- 🧠 Automatically sends players back to the server they disconnected from.
- ⚙️ Define custom rules based on a player's last server. For example, send players from a `bedwars_` game to the most populated `bedwars_lobby`.
- ⚖️ Distributes players across lobbies by connecting them to the server with the **most** or **least** players, or to a **random** one.
- 🛡️ Configure a safe fallback server (or a pool of servers) for new players or when a target server is offline.
- 🧊 Keep players in their frozen, loaded world while a backend restarts (Minecraft 1.20.3–26.3).
- ⚡ Reload the configuration on-the-fly with a simple command.

---

## Installation

1.  Download the plugin
2.  Place the plugin into your Velocity proxy's `/plugins` directory.
3.  Restart your Velocity proxy. A `config.yml` file will be generated in `/plugins/SmartRejoin/`.

---

## Commands & Permissions

| Command | Alias | Permission | Description |
| :--- | :--- | :--- | :--- |
| `/smartrejoinreload` | `/srr` | `smartrejoin.command.reload` | Reloads the `config.yml` file. |
| `/leave` | — | None | Leaves a seamless reconnect session for the lobby. |

---

## Configuration (`config.yml`)

The configuration is split into three main parts: **Fallback**, **Default Rule**, and **Custom Rules**.

### Quick Start

For the simplest setup, just install the plugin. By default (`rule: SAME`), it will automatically reconnect players to the server they left. New players will be handled by your `velocity.toml` configuration. To use the plugin's fallback for new players, set `fallback.enabled: true`.

### Example Use Cases

-   **BedWars Lobby**: If a player leaves `bedwars_12`, send them to the `bedwars_lobby` with the most players.
-   **Persistent Survival**: If a player leaves `survival`, always send them back to `survival`.
-   **Random Lobby**: If a player was in any `lobby_` server, send them to another random `lobby_` server.
-   **AFK Escape**: If a player was in an `afk` server, send them to the `lobby` with the fewest players.

### Routing reference

Custom rules are checked in YAML order; the first matching rule wins. `last_seen.type`
accepts `EQUALS` (case insensitive exact match) or `CONTAINS` (case sensitive substring).
`where_to_join.type` accepts:

| Strategy | Name matching | Selection |
| --- | --- | --- |
| `EQUALS`, `SAME`, `SERVER` | Case insensitive exact name | Attempts that registered server directly, including stopped servers |
| `MOST` | Case sensitive substring | Online server with the most players and a free slot |
| `LEAST` | Case sensitive substring | Online server with the fewest players and a free slot |
| `RANDOM` | Case sensitive substring | Random online server with a free slot |

Servers hiding player counts are eligible for pool selection (counted as zero).
An exact destination receives a real connection attempt so plugins such as AutoServer
can start it. It may still refuse a connection or be full. Unknown names use fallback.
With `fallback.enabled: false`, Velocity's `try` list supplies fallback; keep at least
one usable server there. With fallback enabled, `fallback.type` accepts the same
strategies above (`SERVER` is the usual choice for one fallback).

`default.rule` accepts `SAME`, `FALLBACK`, or `SMART`. Only `SMART` uses
`default.arguments`, a two-element list `[strategy, name_pattern]`. The strategy is
one of the target strategies above. `%last_seen%` expands to the last server name
with trailing digits and adjacent underscores, hyphens, or spaces removed:
`bedwars_123` becomes `bedwars`. Other characters and letter suffixes remain.

```yaml
default:
  rule: SMART
  arguments: ['MOST', '%last_seen%_lobby']
rules:
  survival:
    last_seen:
      type: EQUALS
      name: survival
    where_to_join:
      type: EQUALS
      name: survival
```

Here a player leaving `bedwars_123` goes to the busiest available server containing
`bedwars_lobby`, while `survival` players always attempt `survival`. Invalid SMART
arguments or no eligible pool candidates use fallback.

### Rejoin queues and servers that start on demand

Enable `rejoin_queue.enabled` to retain players' original destination when a backend
fails and return them after it becomes available. This includes players logging in
while their saved server is already down. Successful fallback connections do not
replace their saved destination while waiting. Disconnecting removes the live queue
entry; their next login still attempts their saved server.

| Setting under `rejoin_queue` | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Enable backend monitoring and automatic return |
| `monitor_interval` | `100` | Ticks between backend pings (5 seconds); minimum 1 |
| `rejoin_initial_delay` | `60` | Ticks to wait after recovery before returning the first player |
| `rejoin_cooldown` | `10` | Ticks between return attempts; minimum 1 |
| `fallback_wait_timeout` | `200` | Ticks to retry connecting to a starting fallback (10 seconds); 0 means one attempt |
| `whitelist.type` | `ALL` | `ALL`, `EQUALS`, or `CONTAINS` |
| `whitelist.list` | Example server names | Names/substrings to monitor; ignored with `ALL` |
| `wait_timeout` | `72000` | Ticks to retain a down-server queue (30 minutes); 0 disables expiry |
| `actionbar.enabled` | `true` | Show queue progress once per second |
| `actionbar.format` | See default config | MiniMessage with `{server}`, `{position}`, `{total}` |
| `leave_command.enabled` | `false` | Register the optional command; no permission required |
| `leave_command.command` | `leavequeue` | Command name without `/` |
| `leave_command.message_left` | See default config | MiniMessage with `{server}` |
| `leave_command.message_not_in_queue` | See default config | MiniMessage shown when no queue exists |
| `debug` | `false` | Detailed queue diagnostics |

All durations are ticks: **20 ticks = 1 second**. Whitelist `EQUALS` ignores case;
`CONTAINS` is case sensitive. Restrict the whitelist to gameplay backends when possible.
A queue requires a usable fallback server: SmartRejoin cannot keep a player connected
to a nonexistent backend. Configure AutoServer separately to start the registered
servers on connection requests. Increase `fallback_wait_timeout` if startup is slow.
Failed return attempts remain queued. A successful return completes the queue entry.
The leave command and queue timeout remove automatic return; they do not kick players.

```yaml
fallback:
  enabled: true
  type: SERVER
  name: lobby
rejoin_queue:
  enabled: true
  monitor_interval: 100
  rejoin_initial_delay: 60
  rejoin_cooldown: 10
  fallback_wait_timeout: 200
  wait_timeout: 72000
  whitelist:
    type: EQUALS
    list: [survival, creative]
```

### Seamless reconnect (verified protocols 765–777: Minecraft 1.20.3–26.3)

Seamless reconnect is an alternative to moving connected players to fallback during a
backend outage. Their existing chunks, blocks, and entities remain visible. Native
client world ticks pause; spectator restrictions, zero movement speed, fixed-position
corrections, and a packet filter prevent movement and interactions. Proxy keepalives
keep the client connected while the backend is unavailable. The actionbar is refreshed
once per second until they return or leave.

```yaml
seamless_reconnect:
  enabled: true
  whitelist:
    type: EQUALS
    list: [survival, creative]
  actionbar: "The server is restarting, /leave to return to lobby"
  recovery_grace: 40
  rejoin_delay: 20
  restart_kick_messages:
    - "server closed"
    - "restarting"
    - "server is stopping"
    - "multiplayer.disconnect.server_shutdown"
```

- `enabled` defaults to **true for fresh installs**. Existing configurations missing
  the setting receive **false** on upgrade; enable it explicitly to opt in.
- `whitelist` selects which backends use seamless reconnect, independently of the
  ordinary rejoin queue whitelist. `type: ALL` (default) includes every backend;
  `EQUALS` matches exact names ignoring case; `CONTAINS` matches case-sensitive
  substrings. An empty list with `EQUALS`/`CONTAINS` selects none. Other backends use
  normal fallback. Apply selection changes with `/srr`; sessions already frozen
  continue until they reconnect or use `/leave`.
- `actionbar` accepts MiniMessage. The example above is the exact default text.
- `recovery_grace` is the delay after failure before checking recovery (default 40
  ticks, minimum 20). Recovery then requires two consecutive successful pings,
  checked once per second, to avoid reconnecting to a backend still shutting down.
- `rejoin_delay` is an extra delay in ticks after stable recovery (20 = 1 second).
  Failed connections are retried.
- An abrupt backend disconnect or timeout triggers freezing. Explicit kick packets
  trigger it only when their text contains a `restart_kick_messages` phrase (case
  insensitive). Ordinary kicks keep normal Velocity handling. Use `[]` to freeze only
  unexpected connection losses; customize phrases for your backend's shutdown text.
- `/leave` exits to the configured fallback or Velocity's `try` list. It works only
  for the waiting player; gameplay and other commands are blocked while frozen. If
  the lobby is unavailable, the player remains waiting and can try `/leave` again.
- Seamless waiting is independent of `rejoin_queue.enabled` and has no expiry. Its
  actionbar takes precedence over the ordinary queue actionbar.
- Frozen worlds exist only in the currently connected client. Disconnecting destroys
  the session. A new login follows the ordinary rules, excluding backends currently
  known to be unavailable, and uses fallback instead of restoring a frozen world.
- This release ships verified wire adapters for protocols **765–777**, covering
  Minecraft **1.20.3–26.3**, including 26.2 (776) and 26.3 (777). There is no runtime
  maximum-version check: a protocol is eligible whenever its verified adapter exists.
  Future releases may still support native freezing, but packet IDs/layouts must be
  verified before use. Missing modern adapters produce a warning once per protocol
  and use normal fallback. Clients older than 1.20.3 use normal fallback.
- The internal network hooks are tested on Velocity 3.4.0 build 563 and Velocity
  4.3.1 build 45. Use the Java version required by your proxy (JDK 25 for Velocity
  4.3.1); the plugin itself is compiled for Java 21.

The input lock temporarily uses spectator mode to prevent local block interactions;
the destination backend restores its normal game mode on successful reconnection.
The backend's world state remains authoritative: after return, it sends its current
world, which may differ from the retained image. If a backend disconnects after it has
already begun a new configuration handshake, the client may need to reconnect.

### Authentication, storage, and reloads

NavAuth is detected automatically. While its API reports a player unauthenticated,
SmartRejoin leaves limbo routing to NavAuth and does not save the limbo server as their
last destination. Routing resumes after an authenticated backend connection. If the
NavAuth API is unavailable, SmartRejoin defers to authentication rather than routing
that player. No NavAuth installation is required for other servers.

`storage.type` accepts `yaml` (default, `data.yml`) or `sqlite` (SQLite database).
Changing storage requires a proxy restart; `/srr` reloads routing and queue settings
and clears existing live queues. Back up player data before changing storage backends.
`settings.logging_enabled` enables informational and warning logs; errors always log.
See [the default config](src/main/resources/config.yml) for all message templates.

`modded_routing` is optional and takes precedence over ordinary rules for returning
players. Current matching uses protocol version only; `loader_type` does not reliably
identify a loader at initial login. Keep it disabled unless version-only routing fits
your network. Its target must be registered, online, and have room.

### Building and testing

Use JDK 21 and run `bash gradlew clean build`. Verify generated packet metadata with
`npm --prefix tools ci && npm --prefix tools run check:protocols`. The deployable release artifact is
`build/libs/SmartRejoin-1.4.2.jar`; it includes the SQLite driver. The `-plain.jar`
is for development and does not bundle runtime dependencies.
