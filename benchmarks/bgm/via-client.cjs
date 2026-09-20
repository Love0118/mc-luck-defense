// Run with NODE_PATH pointing at a minecraft-protocol installation; this is not an audio renderer.
const mc=require('minecraft-protocol');
const fs=require('fs'),crypto=require('crypto');
const output=process.argv[2];
const results={};let ended=0;
for(const username of ['MudBgmOwner','MudBgmViewer']) {
  const record=results[username]={version:'1.21.8',adds:[],removes:[],sounds:[],errors:[],loaded:{}};
  const client=mc.createClient({host:'127.0.0.1',port:25587,username,version:'1.21.8',auth:'offline',disableChatSigning:true});
  client.on('position',p=>{client.write('teleport_confirm',{teleportId:p.teleportId});client.write('player_loaded',{});});
  client.on('ping',p=>client.write('pong',{id:p.id}));
  client.on('chunk_batch_finished',()=>client.write('chunk_batch_received',{chunksPerTick:64}));
  client.on('add_resource_pack',async p=>{
    try {
      record.adds.push({uuid:p.uuid,hash:p.hash,url:p.url});
      client.write('resource_pack_receive',{uuid:p.uuid,result:3});
      const bytes=Buffer.from(await (await fetch(p.url)).arrayBuffer());
      if(crypto.createHash('sha1').update(bytes).digest('hex')!==p.hash)throw Error('Hash mismatch');
      if(bytes.readUInt32LE(0)!==0x04034b50)throw Error('Not a ZIP');
      client.write('resource_pack_receive',{uuid:p.uuid,result:4});
      // Hold the third apply response: partial success and DOWNLOADED must not start playback.
      const delay=p.url.includes('fixture2')?(username==='MudBgmOwner'?3500:5500):100;
      setTimeout(()=>{
        record.loaded[p.uuid]=Date.now();
        client.write('resource_pack_receive',{uuid:p.uuid,result:0});
      },delay);
    }catch(e){record.errors.push(String(e));}
  });
  client.on('remove_resource_pack',p=>record.removes.push(p));
  client.on('packet',(p,meta)=>{
    if(!['entity_sound_effect','sound_effect'].includes(meta.name))return;
    const name=p.sound?.data?.soundName;
    if(!name?.startsWith('mud_bgm:'))return;
    record.sounds.push({name,category:p.soundCategory,time:Date.now()});
    if(Object.keys(record.loaded).length!==3)record.errors.push('BGM before all apply responses');
    if(p.soundCategory!=='record')record.errors.push('Wrong sound category');
  });
  client.on('error',e=>record.errors.push(String(e)));
  client.on('end',()=>{if(++ended===2){fs.writeFileSync(output,JSON.stringify(results,null,2));process.exit(0);}});
}
setTimeout(()=>{fs.writeFileSync(output,JSON.stringify(results,null,2));process.exit(2);},90000).unref();
