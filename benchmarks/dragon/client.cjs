const mc=require("minecraft-protocol"),fs=require("fs");
const result={version:"1.21.8",players:{}};
let ended=0;
for(const username of ["DragonOwner","DragonViewer"]){
 const state=result.players[username]={position:false,errors:[],spawns:[],metadata:[],moves:[]};
 const client=mc.createClient({host:"127.0.0.1",port:25586,username,version:"1.21.8",auth:"offline"});
 client.on("packet",(data,meta)=>{
  if(meta.name==="position"){state.position=true;client.write("teleport_confirm",{teleportId:data.teleportId});}
  if(meta.name==="spawn_entity")state.spawns.push({id:data.entityId,type:data.type});
  if(meta.name==="entity_metadata")state.metadata.push(data);
  if(["rel_entity_move","entity_move_look","entity_teleport","entity_position_sync"].includes(meta.name))state.moves.push({packet:meta.name,...data});
 });
 client.on("error",e=>state.errors.push(e.message));
 client.on("end",()=>{if(++ended===2){fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2));process.exit(0);}});
}
