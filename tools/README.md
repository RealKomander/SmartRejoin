# Protocol integration tests

Build the plugin with JDK 21 using `bash gradlew clean build` from the repository
root. Download an official Velocity 3.4.0 JAR, then install the test dependencies:

```sh
npm --prefix tools ci
VELOCITY_JAR=/absolute/path/velocity.jar \
PLUGIN_JAR="$PWD/build/libs/SmartRejoin-1.4.2.jar" \
MC_VERSION=1.20.4 LONG_SMOKE=1 node tools/seamless-smoke.js
```

The harness starts an isolated offline proxy on localhost:32565 and two test
backends on ports 32566/32567. Those ports must be free. It creates and prints a
temporary directory containing its config, data, and proxy log. It never touches
an existing proxy installation. Requires Node.js 22+ and JDK 21.

It verifies native tick freezing without respawn/entity/chunk removal, fixed
position after movement input, zero movement speeds, persistent actionbar,
keepalives, automatic backend return, `/leave`, and logout while frozen followed
by ordinary login to lobby. `LONG_SMOKE=1` holds the outage past Velocity's 30-second
read timeout. Repeat with `MC_VERSION=1.21.4` to test the newer position format, or
`MC_VERSION=1.21.11` for the newest protocol supported by Velocity 3.4.0 build 563.
`STORAGE=sqlite` also exercises YAML-to-SQLite migration and the bundled JDBC driver.

These are automated wire-level tests; they do not render a graphical Minecraft
client. Frozen entity/block rendering uses the client's native ticking-state
packet. The protocol table is generated from PrismarineJS minecraft-data (MIT):

```sh
npm --prefix tools run generate
```

`QUEUE_SMOKE=1 STARTING_LOBBY=1` instead exercises issue #4: a login with an already
stopped saved backend, a fallback that starts after the initial failed attempt,
preservation of the saved destination, and ordinary queue return after recovery.
`LEAVE_FAILURE=1` also tests `/leave` while lobby is unavailable; `COMPRESSION=-1`
disables compression to exercise both Netty pipeline layouts. `RELOAD=1` checks
that a config reload preserves active frozen sessions. The standard seamless
scenario includes both abrupt disconnects and explicit restart kick packets, and
checks that ordinary kicks do not trigger a frozen session.

Java unit/regression tests run through `bash gradlew test`. The generated wire
adapter uses verified IDs/layouts rather than guessing for unknown protocols. The
runtime has no maximum-version check; a missing modern adapter logs a warning and
uses ordinary fallback. `npm --prefix tools run check:protocols` checks that the
shipped table matches generation. Generation now fails if a listed release lacks a
schema or the position/abilities/ticking layout changes, rather than omitting it.
The packaged table and Java tests cover every protocol from 765 through 777.

`WHITELIST_SMOKE=1` restricts seamless reconnect to `survival`, runs the selected
backend outage tests, then verifies that a lobby outage uses ordinary fallback
instead of freezing.

## Protocol 777 / Minecraft 26.3

The npm minecraft-data 3.119.0 release lists 26.2/26.3 but does not include their
schemas. The plugin table therefore includes small pinned projections in
`freeze-protocol-overrides.json`, derived from
[minecraft-data PR 1301](https://github.com/PrismarineJS/minecraft-data/pull/1301)
(commit `924e26d6b3bb8f97a5d1e73d6190b09879c2e1e2`) and cross-checked against
[ViaVersion's 26.3 packet definitions](https://github.com/ViaVersion/ViaVersion/tree/4d5d5e3e7bc5c4f29455ce3181e03320cc60af4c/common/src/main/java/com/viaversion/viaversion/protocols/v26_2to26_3/packet).
These are development-time inputs; the plugin needs no Node dependencies.

For an end-to-end 777 test, use Velocity 4.3.1 build 45 and its required JDK 25.
The currently published test client also lacks complete 26.3 support, so create an
isolated fixture using the pinned upstream client branch and data:

```sh
mkdir -p /tmp/smartrejoin-777-tools /tmp/smartrejoin-777-data
npm --prefix /tmp/smartrejoin-777-tools install --no-audit --no-fund \
  https://codeload.github.com/DallasCarraher/node-minecraft-protocol/tar.gz/a1a33809c49553e544a7efd3d8bc03eb0867f4ab
curl -fL https://codeload.github.com/DallasCarraher/minecraft-data/tar.gz/924e26d6b3bb8f97a5d1e73d6190b09879c2e1e2 \
  -o /tmp/smartrejoin-777-data.tar.gz
tar -xzf /tmp/smartrejoin-777-data.tar.gz --strip-components=1 -C /tmp/smartrejoin-777-data
JAVA_BIN=/absolute/path/jdk25/bin/java \
VELOCITY_JAR=/absolute/path/velocity-4.3.1-45.jar \
PLUGIN_JAR="$PWD/build/libs/SmartRejoin-1.4.2.jar" \
MC_VERSION=26.3 \
MC_PROTOCOL_MODULE=/tmp/smartrejoin-777-tools/node_modules/minecraft-protocol \
MC_DATA_MODULE=/tmp/smartrejoin-777-tools/node_modules/minecraft-data \
MC_DATA_OVERLAY=/tmp/smartrejoin-777-data \
WHITELIST_SMOKE=1 RELOAD=1 LONG_SMOKE=1 node tools/seamless-smoke.js
```

`load-protocol-test-overlay.js` registers the extra schemas and feature data only
in that process, without editing installed dependencies. The simulated 777 backend
supplies the new login session UUID and encodes previous game mode as zero (absent
optional VarInt), avoiding the scaffold's stale 255 sentinel. Teleport acknowledgments
include the new position/rotation fields. `DECODE_DEBUG=1` enables proxy packet-decode
logging and stops the fixture at the first proxy error.
