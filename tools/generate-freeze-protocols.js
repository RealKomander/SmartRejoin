// Run with: NODE_PATH=/path/to/node_modules node tools/generate-freeze-protocols.js
// Source: PrismarineJS/minecraft-data 3.119.0 (MIT), release protocol schemas.
// The generated table is used at runtime; Node is not needed by the plugin.
const fs = require('fs');
const md = require('minecraft-data');
const rows = new Map();
for (const version of md.versions.pc.filter(v => v.releaseType === 'release' && v.version >= 765)) {
  const data = md(version.minecraftVersion);
  if (!data || rows.has(data.version.version)) continue;
  const mappings = dir => data.protocol.play[dir].types.packet[1][0].type[1].mappings;
  const id = (dir, name) => {
    const entry = Object.entries(mappings(dir)).find(([, value]) => value === name);
    return entry ? parseInt(entry[0], 16) : -1;
  };
  const values = [
    ...['abilities', 'position', 'set_ticking_state', 'keep_alive', 'game_state_change', 'start_configuration']
      .map(name => id('toClient', name)),
    ...['position', 'look', 'position_look', 'chat_command', 'chat_command_signed', 'chat', 'keep_alive']
      .map(name => id('toServer', name)),
    data.protocol.play.toClient.types.packet_position[1][0].name === 'teleportId' ? 1 : 0
  ];
  if (values.slice(0, 6).some(x => x < 0)) continue;
  rows.set(data.version.version, values.join(','));
}
fs.writeFileSync('src/main/resources/freeze-protocols.properties',
  '# Generated from PrismarineJS/minecraft-data 3.119.0 by tools/generate-freeze-protocols.js\n' +
  '# protocol=abilities,position,ticking,keepalive,gamestate,configuration,move,look,moveLook,command,signedCommand,chat,keepaliveReply,newPositionFormat\n' +
  [...rows].sort((a,b) => a[0]-b[0]).map(([k,v]) => `${k}=${v}\n`).join(''));
