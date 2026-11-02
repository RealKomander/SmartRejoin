const mc = require('minecraft-protocol');
const md = require('minecraft-data');
const {spawn} = require('child_process');
const fs = require('fs');
const assert = require('assert');
const path = require('path');
const os = require('os');
if (!process.env.VELOCITY_JAR || !process.env.PLUGIN_JAR) {
  throw new Error('Set VELOCITY_JAR and PLUGIN_JAR to absolute paths to the proxy and built plugin JARs.');
}
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'smartrejoin-protocol-'));
fs.mkdirSync(path.join(work, 'plugins'));
fs.copyFileSync(process.env.VELOCITY_JAR, path.join(work, 'velocity.jar'));
fs.copyFileSync(process.env.PLUGIN_JAR, path.join(work, 'plugins', 'SmartRejoin.jar'));
process.chdir(work);
fs.writeFileSync('velocity.toml', `config-version = "2.7"
bind = "127.0.0.1:32565"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
[servers]
lobby = "127.0.0.1:32566"
survival = "127.0.0.1:32567"
factions = "127.0.0.1:32566"
minigames = "127.0.0.1:32566"
try = ["lobby"]
[advanced]
compression-threshold = ${process.env.COMPRESSION || 256}
login-ratelimit = 0
`);
console.log('Test directory: '+work);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const version = process.env.MC_VERSION || '1.20.4';
const data = md(version);
const joins = [];
let errors = [];
let client;
let relog;
let proxy;
let survival;
let lobby;
const packets = [];
const proxyLog = [];
function backend(name, port) {
  const server = mc.createServer({version, host:'127.0.0.1', port, 'online-mode':false, keepAlive:true, hideErrors:true});
  server.on('error', e => errors.push(`${name}: ${e.stack}`));
  server.on('playerJoin', c => {
    joins.push(name+':'+c.username);
    c.on('error',e=>errors.push(`${name} client: ${e.message}`));
    c.write('login',{...data.loginPacket, entityId: name==='survival'?11:22, gameMode:0, previousGameMode:0, enforceSecureChat:false, hashedSeed:[0,0], maxPlayers:20});
    c.write('abilities',{flags:0,flyingSpeed:0.05,walkingSpeed:0.1});
    const position={x:10,y:80,z:20,yaw:45,pitch:15,flags:0,teleportId:1};
    if(data.version.version>=768)Object.assign(position,{dx:0,dy:0,dz:0});
    c.write('position',position);
    c.write('set_ticking_state',{tick_rate:20,is_frozen:false});
    c.write('spawn_entity', {entityId:12345,objectUUID:'12345678-1234-1234-1234-123456789012',type:data.entitiesByName.zombie.id,
      x:12,y:80,z:22,pitch:0,yaw:0,headPitch:0,objectData:0,velocity:{x:0,y:0,z:0}});
  });
  return server;
}
async function waitUntil(fn,description,timeout=15000) {
 const end=Date.now()+timeout;
 while(!fn()) {if(Date.now()>end)throw Error('Timeout: '+description+'\nproxy:\n'+proxyLog.join(''));await sleep(50);}
}
async function stopServer(server, restartKick=false) {
  if (restartKick) {
    for (const c of Object.values(server.clients)) c.write('kick_disconnect', {reason:{type:'compound',name:'',value:{text:{type:'string',value:'Server closed'}}}});
    await sleep(100);
  }
  for(const c of Object.values(server.clients))c.socket.destroy();
  server.close();
  await sleep(300);
}
function connect(name) {
 const c=mc.createClient({host:'127.0.0.1',port:32565,username:name,version,auth:'offline'});
 c.on('error',e=>errors.push('client:'+e.message));
 c.on('packet',(packet,meta)=>{if(['login','respawn','position','set_ticking_state','abilities','action_bar','game_state_change','spawn_entity','entity_destroy','unload_chunk'].includes(meta.name))packets.push({user:name,name:meta.name,...packet});});
 c.on('end',reason=>console.log('CLIENT END',reason));
 c.on('position',p=>c.write('teleport_confirm',{teleportId:p.teleportId}));
 return c;
}
(async()=>{
 const queueMode=process.env.QUEUE_SMOKE==='1';
 lobby=process.env.STARTING_LOBBY==='1'?null:backend('lobby',32566);
 survival=queueMode?null:backend('survival',32567);
 fs.mkdirSync('plugins/smartrejoin',{recursive:true});
 fs.writeFileSync('plugins/smartrejoin/config.yml',`storage:\n  type: ${process.env.STORAGE || 'yaml'}\nsettings:\n  logging_enabled: true\nfallback:\n  enabled: true\n  type: SERVER\n  name: lobby\nrejoin_queue:\n  enabled: ${queueMode}\n  monitor_interval: 2\n  rejoin_initial_delay: 0\n  rejoin_cooldown: 1\nseamless_reconnect:\n  enabled: ${!queueMode}\n  whitelist:\n    type: ${process.env.WHITELIST_SMOKE === '1' ? 'EQUALS' : 'ALL'}\n    list: [survival]\n  rejoin_delay: 0\ndefault:\n  rule: SAME\n`);
 // Preseed this offline UUID's destination.
 const crypto=require('crypto');const hash=crypto.createHash('md5').update('OfflinePlayer:FreezeTester').digest();hash[6]=(hash[6]&15)|48;hash[8]=(hash[8]&63)|128;const h=hash.toString('hex');const uuid=`${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
 const rh=crypto.createHash('md5').update('OfflinePlayer:RelogTester').digest();rh[6]=(rh[6]&15)|48;rh[8]=(rh[8]&63)|128;const r=rh.toString('hex');const relogUuid=`${r.slice(0,8)}-${r.slice(8,12)}-${r.slice(12,16)}-${r.slice(16,20)}-${r.slice(20)}`;
 fs.writeFileSync('plugins/smartrejoin/data.yml',`${uuid}: survival\n${relogUuid}: survival\n`);
 proxy=spawn('java',['-Dterminal.jline=false','-Dterminal.ansi=false','-jar','velocity.jar'],{stdio:['pipe','pipe','pipe']});
 proxy.stdout.on('data',d=>proxyLog.push(d.toString()));proxy.stderr.on('data',d=>proxyLog.push(d.toString()));
 await waitUntil(()=>proxyLog.some(l=>l.includes('Done (')),'proxy startup');
 if (queueMode) {
   client=connect('FreezeTester');
   if (!lobby) setTimeout(()=>{lobby=backend('lobby',32566);},1500);
   await waitUntil(()=>joins.includes('lobby:FreezeTester'),'fallback for already-down saved server');
   await waitUntil(()=>packets.some(p=>p.name==='action_bar'),'rejoin queue actionbar');
   const saved=fs.readFileSync('plugins/smartrejoin/data.yml','utf8');
   assert(saved.includes(`${uuid}: survival`),'fallback must preserve saved destination');
   survival=backend('survival',32567);
   await waitUntil(()=>joins.includes('survival:FreezeTester'),'queue return after startup');
   console.log('PASS already-down login, starting fallback retry, saved destination, ordinary queue return');
   return;
 }
 client=connect('FreezeTester');
 await waitUntil(()=>joins.includes('survival:FreezeTester')&&packets.some(p=>p.name==='position'),'initial survival join');
 relog=connect('RelogTester');
 await waitUntil(()=>joins.includes('survival:RelogTester'),'second player join');
 await sleep(500);
 const outageStart=packets.length;
 await stopServer(survival);
 await waitUntil(()=>packets.slice(outageStart).some(p=>p.name==='set_ticking_state'&&p.is_frozen),'world freeze');
 const frozen=packets.slice(outageStart);
 assert(!frozen.some(p=>['login','respawn','entity_destroy','unload_chunk'].includes(p.name)),'world must not reload or remove entities/chunks while freezing');
 assert(packets.slice(0,outageStart).some(p=>p.name==='spawn_entity'),'visible entity seeded before outage');
 assert(frozen.some(p=>p.name==='abilities'&&p.flyingSpeed===0&&p.walkingSpeed===0),'movement lock');
 client.write('position',{x:200,y:100,z:200,onGround:true,flags:{onGround:true,hasHorizontalCollision:false}});
 await sleep(1200);
 const corrections=packets.slice(outageStart).filter(p=>p.name==='position');
 assert(corrections.length>2,'repeated position lock');
 assert(corrections.every(p=>p.x===10&&p.y===80&&p.z===20),'fixed original pose');
 assert(packets.slice(outageStart).some(p=>p.name==='action_bar'),'persistent actionbar');
 console.log('PASS freeze, native ticks, movement lock, retained world, actionbar');
 if(process.env.RELOAD==='1') {
   proxy.stdin.write('srr\n');
   await waitUntil(()=>proxyLog.some(l=>l.includes('Configuration reloaded successfully.')),'reload while frozen');
 }
 await sleep(2100);
 assert(packets.slice(outageStart).filter(p=>p.user==='FreezeTester'&&p.name==='action_bar').length>=3,'actionbar refreshes throughout outage');
 // Wait beyond Velocity's 30s read timeout to prove proxy keepalives work.
 await sleep(process.env.LONG_SMOKE==='1'?32000:5500);
 assert(!client.ended,'client remains connected through outage');
 console.log('PASS outage keepalive');
 relog.end(); await sleep(500);
 const relogStart=packets.length;
 relog=connect('RelogTester');
 await waitUntil(()=>joins.includes('lobby:RelogTester'),'logout while frozen then fresh login');
 await sleep(500);
 assert(!packets.slice(relogStart).some(p=>p.user==='RelogTester'&&p.name==='set_ticking_state'&&p.is_frozen),'logout must destroy frozen session');
 assert(!packets.slice(outageStart).some(p=>p.user==='FreezeTester'&&(p.name==='login'||p.name==='respawn')),'other player stays in retained world');
 console.log('PASS logout while frozen followed by normal login to lobby');
 survival=backend('survival',32567);
 await waitUntil(()=>joins.filter(j=>j==='survival:FreezeTester').length>=2,'automatic return',20000);
 await waitUntil(()=>packets.slice(outageStart).filter(p=>p.name==='set_ticking_state'&&!p.is_frozen).length>=1,'unfreeze');
 console.log('PASS automatic backend return');
 await sleep(700);
 const second=packets.length;
 await stopServer(survival, true);
 await waitUntil(()=>packets.slice(second).some(p=>p.name==='set_ticking_state'&&p.is_frozen),'second freeze');
 if (process.env.LEAVE_FAILURE==='1') {
   await stopServer(lobby);
   client.write('chat_command',{command:'leave',timestamp:BigInt(Date.now()),salt:0n,argumentSignatures:[],messageCount:0,acknowledged:Buffer.alloc(3)});
   await sleep(1000);
   assert(!client.ended && !joins.includes('lobby:FreezeTester'),'failed leave retains frozen world');
   lobby=backend('lobby',32566);
   await waitUntil(()=>joins.filter(j=>j==='lobby:RelogTester').length>=2,'stable lobby recovery');
 }
 client.write('chat_command',{command:'leave',timestamp:BigInt(Date.now()),salt:0n,argumentSignatures:[],messageCount:0,acknowledged:Buffer.alloc(3)});
 await waitUntil(()=>joins.includes('lobby:FreezeTester'),'leave to lobby');
 console.log('PASS explicit restart kick and /leave to lobby');
 client.end(); await sleep(500);
 // The backend is still down: reconnect must route to lobby, never frozen world.
 const reconnectStart=packets.length;
 client=connect('FreezeTester');
 await waitUntil(()=>joins.filter(j=>j==='lobby:FreezeTester').length>=2,'fresh login fallback');
 await sleep(600);
 assert(!packets.slice(reconnectStart).some(p=>p.name==='set_ticking_state'&&p.is_frozen),'no fake world on fresh login');
 console.log('PASS fresh login routes normally');
 const ordinaryKickStart=packets.length;
 for(const c of Object.values(lobby.clients)) if(c.username==='FreezeTester') c.write('kick_disconnect', {reason:{type:'compound',name:'',value:{text:{type:'string',value:'You are banned'}}}});
 await waitUntil(()=>client.ended,'ordinary kick disconnect');
 assert(!packets.slice(ordinaryKickStart).some(p=>p.user==='FreezeTester'&&p.name==='set_ticking_state'&&p.is_frozen),'ordinary kick must not freeze');
 console.log('PASS ordinary kicks do not trigger seamless');
 if (process.env.WHITELIST_SMOKE === '1') {
   const excludedStart=packets.length;
   await stopServer(lobby);
   await waitUntil(()=>relog.ended,'excluded backend uses normal disconnect/fallback');
   assert(!packets.slice(excludedStart).some(p=>p.user==='RelogTester'&&p.name==='set_ticking_state'&&p.is_frozen),'excluded backend must not freeze');
   console.log('PASS selected backend freezes; excluded backend uses normal fallback');
 }
 assert(!proxyLog.some(l=>/ERROR|could not attach|Cannot retain/.test(l)),proxyLog.join(''));
 console.log('PASS Velocity logs clean; '+version);
})().catch(e=>{console.error(e.stack);console.error(proxyLog.join(''));process.exitCode=1;}).finally(async()=>{
 if(client)client.end();
 if(relog)relog.end();
 for(const s of [survival,lobby])if(s)try{await stopServer(s);}catch{}
 if(proxy && proxy.exitCode===null){proxy.stdin.write('shutdown\n');await sleep(1000);proxy.kill('SIGTERM');}
 fs.writeFileSync('protocol-smoke.log',proxyLog.join(''));
 console.log('Proxy log: '+path.join(work,'protocol-smoke.log'));
 setTimeout(()=>process.exit(process.exitCode||0),500);
});
