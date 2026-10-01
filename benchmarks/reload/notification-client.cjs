const mc=require('minecraft-protocol'),fs=require('fs');
const data=require('minecraft-data')('1.21.8');
const particle=structuredClone(data.protocol.types.Particle);
const particleFields=particle[1].find(f=>f.name==='data').type[1].fields;
particleFields.dust='reloadDust';particleFields.dust_color_transition='reloadDustTransition';
const customPackets={[data.version.majorVersion]:{types:{Particle:particle,
 reloadDust:['container',[{name:'color',type:'i32'},{name:'scale',type:'f32'}]],
 reloadDustTransition:['container',[{name:'fromColor',type:'i32'},{name:'toColor',type:'i32'},{name:'scale',type:'f32'}]]}}};
const result={version:'1.21.8',players:{}};let ended=0;
const timeout=setTimeout(()=>{fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2));process.exit(1);},120000);
for(const username of ['ReloadOwner','ReloadViewer','ReloadLobby']) {
 const state=result.players[username]={position:false,errors:[],disconnect:null,completed:false,settingsWindows:0,phases:{}};
 let phase='INITIAL';
 const client=mc.createClient({host:'127.0.0.1',port:25585,username,version:'1.21.8',auth:'offline',customPackets});
 client.on('packet',(data,meta)=>{
  if(meta.name==='position'){state.position=true;client.write('teleport_confirm',{teleportId:data.teleportId});}
  const text=JSON.stringify(data);
  if(meta.name==='disconnect'||meta.name==='kick_disconnect')state.disconnect=text;
  if(meta.name==='open_window' && text.includes('환경설정'))state.settingsWindows++;
  if(meta.name==='system_chat') {
   const marker=text.match(/notification-phase:([A-Z_]+)/);
   if(marker){phase=marker[1];state.phases[phase]={summonChat:0,sounds:0,roundChat:0};}
   if(text.includes('notification-fixture-done'))state.completed=true;
   if(state.phases[phase]) {
    if(text.includes('획득!') && text.includes('진 태초'))state.phases[phase].summonChat++;
    if(text.includes('라운드 ') && text.includes(' · '))state.phases[phase].roundChat++;
   }
  }
  if((meta.name==='sound_effect'||meta.name==='named_sound_effect') && state.phases[phase]) {
   if(text.includes('ui.toast.challenge_complete'))state.phases[phase].sounds++;
  }
 });
 client.on('error',e=>state.errors.push(e.message));
 client.on('end',()=>{if(++ended===3){clearTimeout(timeout);fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2));process.exit(0);}});
}
