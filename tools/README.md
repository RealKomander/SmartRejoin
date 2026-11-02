# Protocol integration tests

Build the plugin with JDK 21 using `bash gradlew clean build` from the repository
root. Download an official Velocity 3.4.0 JAR, then install the test dependencies:

```sh
npm --prefix tools ci
VELOCITY_JAR=/absolute/path/velocity.jar \
PLUGIN_JAR="$PWD/build/libs/SmartRejoin-1.4.1.jar" \
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
adapter deliberately has no fallback guess for unknown protocol IDs.

`WHITELIST_SMOKE=1` restricts seamless reconnect to `survival`, runs the selected
backend outage tests, then verifies that a lobby outage uses ordinary fallback
instead of freezing.
