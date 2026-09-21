const mc=require("minecraft-protocol"),fs=require("fs");
const data=require("minecraft-data")("1.21.8");
// Installed minecraft-data still describes pre-1.21.5 float RGB dust. Mojang 1.21.8 uses packed INT RGB.
const particle=structuredClone(data.protocol.types.Particle);
const particleFields=particle[1].find(f=>f.name==="data").type[1].fields;
particleFields.dust="reloadDust";particleFields.dust_color_transition="reloadDustTransition";
const customPackets={[data.version.majorVersion]:{types:{Particle:particle,
 reloadDust:["container",[{name:"color",type:"i32"},{name:"scale",type:"f32"}]],
 reloadDustTransition:["container",[{name:"fromColor",type:"i32"},{name:"toColor",type:"i32"},{name:"scale",type:"f32"}]]}}};
const result={version:"1.21.8",players:{}};let ended=0;
for(const username of ["ReloadOwner","ReloadViewer"]){
 const state=result.players[username]={position:false,errors:[],completed:false};
 const client=mc.createClient({host:"127.0.0.1",port:25585,username,version:"1.21.8",auth:"offline",customPackets});
 client.on("packet",(data,meta)=>{
  if(meta.name==="position"){state.position=true;client.write("teleport_confirm",{teleportId:data.teleportId});}
  if(meta.name==="system_chat" && JSON.stringify(data).includes("reload-fixture-done"))state.completed=true;
 });
 client.on("error",e=>state.errors.push(e.message));
 client.on("end",()=>{if(++ended===2){fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2));process.exit(0);}});
}
