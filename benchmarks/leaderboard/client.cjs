const mc=require('minecraft-protocol'),fs=require('fs'),path=require('path');
const directory=process.argv[2],result={};let ended=0;
for(const username of ['RankFirst','RankSecond']){
  const r=result[username]={spawns:[],metadata:[],errors:[]};
  const client=mc.createClient({host:'127.0.0.1',port:25592,username,version:'1.21.8',auth:'offline'});
  client.on('position',p=>{client.write('teleport_confirm',{teleportId:p.teleportId});client.write('player_loaded',{});});
  client.on('ping',p=>client.write('pong',{id:p.id}));
  client.on('chunk_batch_finished',()=>client.write('chunk_batch_received',{chunksPerTick:64}));
  client.on('spawn_entity',p=>r.spawns.push(p.entityId));
  client.on('entity_metadata',p=>r.metadata.push(p));
  client.on('error',e=>r.errors.push(String(e)));
  client.on('end',()=>{if(++ended===2){fs.writeFileSync(path.join(directory,'clients.json'),JSON.stringify(result,null,2));process.exit(0);}});
  if(username==='RankFirst'){
    const poll=setInterval(()=>{
      const file=path.join(directory,'leaderboard-ids.json');
      if(!fs.existsSync(file)||client.state!=='play')return;
      clearInterval(poll);
      const ids=JSON.parse(fs.readFileSync(file));
      // Use the hit-position packet sent by a normal right click.
      client.write('use_entity',{target:ids.next,mouse:2,x:0,y:.3,z:0,hand:0,sneaking:false});
      fs.writeFileSync(path.join(directory,'client-clicked.flag'),'clicked');
    },200);
  }
}
setTimeout(()=>process.exit(2),90000).unref();
