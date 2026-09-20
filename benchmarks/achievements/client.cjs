// Supply installed minecraft-protocol module via NODE_PATH.
const mc=require("minecraft-protocol"),fs=require("fs");
const result={version:"1.21.8",position:false,errors:[],advancements:[],packets:{}};
const client=mc.createClient({host:"127.0.0.1",port:25589,username:"AchievementTest",version:"1.21.8",auth:"offline"});
client.on("packet",(data,meta)=>{
 result.packets[meta.name]=(result.packets[meta.name]||0)+1;
 if(meta.name==="advancements")result.advancements.push(data);
 if(meta.name==="position"){result.position=true;client.write("teleport_confirm",{teleportId:data.teleportId});}
});
client.on("error",e=>result.errors.push(e.message));
client.on("end",()=>{fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2));process.exit(0);});
