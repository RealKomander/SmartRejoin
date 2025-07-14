# SmartRejoin
A Velocity 3.4.0 plugin analogous to RememberMe, but with more capabilities.

## If you're lazy, just install this plugin and it will send players to where they left.
New players will join a server specified in `velocity.toml`. That is unless you configure SmartRejoin, of course.

**More complex usage ideas:**
- if player was on "bedwars_..." server, join them to a bedwars lobby with the most players online
- if a player was on "survival", join them to "survival"
- if a player was in any "lobby_..." server, join them to a random "lobby_..." server
- if a player was in "afk_..." server, join them to a "lobby_..." server with the least players online
**You get it, the possibilities are limitless!**

## Explained in more details in config.yml:
```yml
# --------------------------------------------------- #
#               RejoinPlugin Configuration            #
# --------------------------------------------------- #

# --- General Settings ---
settings:
  # If true, the plugin will print informational and warning messages to the console.
  # Critical errors will always be logged regardless of this setting.
  logging_enabled: false

# --- Fallback Configuration ---
# This section defines what happens if the plugin cannot find a valid server
# for a player according to the rules below.
# This can happen if the target server is offline or full.
fallback:
  # If 'enabled', this plugin's fallback logic will be used.
  # This is highly recommended to override Velocity's default behavior.
  enabled: false

  # Defines the type of fallback to perform.
  # - 'RANDOM': Connects the player to a random server whose name CONTAINS the 'name' value.
  # - 'SERVER': Connects the player to a specific server with an exact 'name'.
  type: RANDOM

  # The server name or part of the name to look for.
  # For example, if type is 'RANDOM' and name is 'lobby', it will find any online server
  # like 'lobby-1', 'lobby-2', etc., and pick one at random.
  name: "lobby"

# --- Default Rule ---
# This rule is applied if a player's last server doesn't match any of the specific rules below.
default:
  # Defines the action to take.
  # - 'SAME': Connects the player back to the exact same server they disconnected from.
  #           If that server is offline, the fallback rule is used.
  # - 'FALLBACK': Immediately uses the fallback rule defined above.
  # - 'SMART': A more advanced option. It tries to find a related lobby server.
  #            It takes the player's last server name, removes common suffixes like numbers
  #            (e.g., 'bedwars_123' becomes 'bedwars'), and then uses the 'arguments'
  #            to find a new server.
  rule: SAME

  # Arguments for the 'SMART' rule. Not used for 'SAME' or 'FALLBACK'.
  # The first argument is the selection strategy ('MOST', 'LEAST', 'RANDOM').
  # The second argument is the name pattern, where '%last_seen%' is a placeholder
  # for the cleaned-up server name from the player's last session.
  #
  # Example: If a player was on 'anarchy_5', %last_seen% becomes 'anarchy'.
  # The plugin would then look for the server with the MOST players whose name contains 'anarchy_lobby'.
  # arguments: ['MOST', '%last_seen%_lobby']
  arguments: []

# --- Custom Rules ---
# Define specific redirection rules here. They are checked in the order they appear.
# The first rule that matches the player's last server will be used.
# Rule names must be unique. They are for your reference.
# 'last_seen' defines the condition for this rule to trigger.
    # Match type:
    # - 'EQUALS': The player's last server name must be an exact match.
    # - 'CONTAINS': The player's last server name must contain this string.
# 'where_to_join' defines where to send the player if the 'last_seen' condition is met.
    # Target selection strategy:
    # - 'EQUALS': Connect to a server with this exact name.
    # - 'MOST': Find all online servers whose names CONTAIN the 'name' string and have at least one slot. Then, connect to the one with the MOST players.
    # - 'LEAST': Same as 'MOST', but connects to the one with the LEAST players.
    # - 'RANDOM': Same as 'MOST', but connects to a RANDOM one.
# Examples are listed below:
rules:
#  stay_on_survival:
#    last_seen:
#      type: EQUALS
#      name: "survival"
#    where_to_join:
#      type: EQUALS
#      name: "survival"
#
#  bedwars_lobby_fallback:
#    last_seen:
#      type: CONTAINS
#      name: "bedwars"
#    where_to_join:
#      type: MOST
#      name: "bedwars_lobby"```
