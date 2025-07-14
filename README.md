# SmartRejoin for Velocity

![Java Version](https://img.shields.io/badge/Java-21+-blue?style=for-the-badge&logo=openjdk)
![Velocity Version](https://img.shields.io/badge/Velocity-3.4.0+-orange?style=for-the-badge)

**SmartRejoin** is a powerful and flexible Velocity plugin that gives you full control over where players connect when they join your network. It's a modern alternative to older reconnect plugins, built with a highly customizable rule-based engine.

---

## Features

- **🧠 Smart Reconnections**: Automatically sends players back to the server they disconnected from.
- **⚙️ Powerful Rule Engine**: Define custom rules based on a player's last server. For example, send players from a `bedwars_` game to the most populated `bedwars_lobby`.
- **⚖️ Load Balancing**: Distribute players across lobbies by connecting them to the server with the **most** or **least** players, or to a **random** one.
- **🛡️ Robust Fallback System**: Configure a safe fallback server (or a pool of servers) for new players or when a target server is offline.
- **🔌 Easy Configuration**: A clean, well-documented `config.yml` makes setup a breeze.
- **⚡ Live Reload**: Reload the configuration on-the-fly with a simple command—no restart required.

---

## Installation

1.  Download the latest release JAR from the [Releases page](https://github.com/RealKomander/SmartRejoin/releases).
2.  Place the `SmartRejoin-1.0.0.jar` file into your Velocity proxy's `/plugins` directory.
3.  Restart your Velocity proxy. A `config.yml` file will be generated in `/plugins/SmartRejoin/`.

---

## Commands & Permissions

| Command | Alias | Permission | Description |
| :--- | :--- | :--- | :--- |
| `/smartrejoinreload` | `/srr` | `smartrejoin.command.reload` | Reloads the `config.yml` file. |

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

### Full Configuration Example (different from default one!)

```yml
# --------------------------------------------------- #
#               SmartRejoin Configuration             #
# --------------------------------------------------- #

# --- General Settings ---
settings:
  # If true, the plugin will print informational messages to the console.
  # Critical errors are always logged.
  logging_enabled: true

# --- Fallback Configuration ---
# Used for new players or when a target server from a rule is offline.
fallback:
  # If 'enabled', this plugin's fallback logic will be used.
  enabled: true
  # 'RANDOM': Connects to a random server whose name CONTAINS the 'name' value.
  # 'SERVER': Connects to a specific server with an exact 'name'.
  type: RANDOM
  name: "lobby"

# --- Default Rule ---
# Applied if a player's last server doesn't match any custom rules below.
default:
  # 'SAME': Connects to the exact same server. (Most common)
  # 'FALLBACK': Immediately uses the fallback rule.
  # 'SMART': Finds a related lobby. See comments below for details.
  rule: SAME
  # Arguments for the 'SMART' rule.
  # Example: ['MOST', '%last_seen%_lobby']
  arguments: []

# --- Custom Rules ---
# Define specific redirection rules here. They are checked in order.
rules:
  stay_on_survival:
    last_seen:
      # Condition: Player's last server name must be an exact match.
      type: EQUALS
      name: "survival"
    where_to_join:
      # Action: Connect to a server with this exact name.
      type: EQUALS
      name: "survival"

  bedwars_lobby_fallback:
    last_seen:
      # Condition: Player's last server name must contain "bedwars".
      type: CONTAINS
      name: "bedwars"
    where_to_join:
      # Action: Find all online servers containing "bedwars_lobby" and
      # connect to the one with the MOST players.
      type: MOST
      name: "bedwars_lobby"
