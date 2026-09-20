package dev.moma.benchmark;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs only on an isolated localhost server with three protocol clients. */
public final class LobbySmokePlugin extends JavaPlugin {
    private List<String> samples;
    private int sample, stage, waitTicks;
    private Object games, maps;
    private Player first, second, viewer;
    private Object firstSession, secondSession;
    private int facedTypes, checkedAttackDirections;
    private int selectedEntityId, glowClearEntityId;
    private long firstTick, secondTick;
    private int expectedSpeed = 2, speedChecks;
    private boolean fractionalGold;
    private Location edgeRecoveryExpected;
    private int portalPhase, portalWait;
    private org.bukkit.block.Block portalBlock;
    private org.bukkit.block.data.BlockData portalOriginal;
    @Override public void onEnable() {
        if (!Bukkit.getIp().equals("127.0.0.1")) throw new IllegalStateException("Localhost only");
        try {
            samples=Files.readAllLines(Path.of("block-samples.tsv"));
            var plugin=Bukkit.getPluginManager().getPlugin("MCLuckDefense");
            games=field(plugin,"games"); maps=field(games,"maps");
            if (call(maps,"get","smoke1")==null) call(maps,"create","smoke1",6);
            if (call(maps,"get","smoke2")==null) call(maps,"create","smoke2",6);
        } catch (Exception error) { throw new RuntimeException(error); }
        Bukkit.getScheduler().runTaskTimer(this,()-> {
            try { tick(); } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE,"LOBBY_SMOKE_FAILED",error); Bukkit.shutdown();
            }
        },1,1);
    }
    private void tick() throws Exception {
        World world=Objects.requireNonNull(Bukkit.getWorld("mud_lobby"));
        if (sample<samples.size()) {
            for (int limit=Math.min(sample+8,samples.size());sample<limit;sample++) {
                String[] row=samples.get(sample).split("\t");
                var actual=world.getBlockAt(Integer.parseInt(row[0]),Integer.parseInt(row[1]),Integer.parseInt(row[2])).getBlockData();
                var expected=Bukkit.createBlockData(row[3]);
                require(actual.matches(expected),"Block mismatch at "+samples.get(sample)+" actual="+actual.getAsString());
            }
            return;
        }
        if (stage==0) {
            first=Bukkit.getPlayerExact("MudBench00"); second=Bukkit.getPlayerExact("MudBench01"); viewer=Bukkit.getPlayerExact("MudBench02");
            if (first==null || second==null || viewer==null || !first.isOnline() || !second.isOnline() || !viewer.isOnline()) return;
            if (!checkLobbyPortal(world)) return;
            require(first.getWorld().equals(world)&&second.getWorld().equals(world),"Login must enter lobby");
            require(first.getLocation().distance(world.getSpawnLocation())<1,"Login must use configured spawn");
            require(!first.getAllowFlight() && !first.isFlying() && !viewer.getAllowFlight(),"Lobby flight disabled");
            require(first.getInventory().getItem(0).getType()==Material.COMPASS,"Lobby session compass granted");
            first.getInventory().setHeldItemSlot(0); sellClick();
            require(first.getOpenInventory().getTopInventory().getSize()==54,"Compass opens session menu");
            first.closeInventory();
            if(Bukkit.getPluginManager().getPlugin("KAKC")!=null)checkKakcInput();
            call(field(games,"tools"),"restore",first);
            first.getInventory().setItem(0,new org.bukkit.inventory.ItemStack(Material.DIAMOND,3));
            first.getInventory().setItem(1,new org.bukkit.inventory.ItemStack(Material.GOLD_INGOT,4));
            call(games,"start",first); call(games,"start",second);
            require(first.getAllowFlight() && first.isFlying() && second.getAllowFlight(),"Participants fly by default");
            require(first.getInventory().getItem(0).getType()==Material.BLAZE_ROD && first.getInventory().getItem(1).getType()==Material.EMERALD,"Tools in slots one and two");
            firstSession=call(games,"session",first); secondSession=call(games,"session",second);
            require(arena(firstSession).coins()==30 && arena(secondSession).coins()==30,"Thirty starting gold");
            require(firstSession!=secondSession,"Distinct session objects");
            require(!arena(firstSession).id().equals(arena(secondSession).id()),"Distinct arenas");
            call(games,"summon",first);
            require(arena(firstSession).defenderCount()==1,"Real summon");
            call(games,"start",viewer);
            require(call(games,"session",viewer)!=null,"Dynamic arena allocation");
            call(games,"leave",viewer);
            require(!viewer.getAllowFlight() && !viewer.isFlying(),"Manual return removes flight");
            // Existing two arenas were full; a third must have been persisted.
            require(((Collection<?>)call(maps,"all")).size()>=3,"Third arena created");
            call(games,"spectate",viewer,first.getName());
            require((boolean)call(games,"watching",viewer),"Spectator registered");
            require(call(games,"session",viewer)==null,"Spectator has no combat session");
            require(viewer.getGameMode()==GameMode.ADVENTURE,"Adventure observer");
            require(viewer.isInvisible(),"Invisible teammate observer");
            require(viewer.getScoreboard()==first.getScoreboard() && viewer.getScoreboard().getEntryTeam(viewer.getName()).canSeeFriendlyInvisibles(),"Translucent teammate rule");
            require(viewer.getInventory().getItem(8).getType()==Material.RED_BED && first.getInventory().getItem(8).getType()==Material.RED_BED,"Slot nine leave beds");
            require(viewer.getAllowFlight() && viewer.isFlying(),"Spectator flight enabled");
            require(first.getInventory().getItem(6).getType()==Material.JUKEBOX && viewer.getInventory().getItem(6).getType()==Material.JUKEBOX,"BGM tools for owner and observer");
            Object bgm=field(games,"bgm");require(bgm!=null,"BGM initialized with SQLite");
            call(bgm,"open",first,false,0);
            require(first.getOpenInventory().getTopInventory().getSize()==18,"BGM manager has two rows");
            require(first.getOpenInventory().getTopInventory().getItem(13).getType()==Material.HOPPER,"BGM upload at bottom center");
            require(first.getOpenInventory().getTopInventory().getItem(17).getType()==Material.BOOK,"BGM library at bottom right");
            first.closeInventory();call(bgm,"toggle",viewer);call(bgm,"toggle",viewer);
            require(first.getInventory().getItem(7).getType()==Material.NOTE_BLOCK
                    && viewer.getInventory().getItem(7).getType()==Material.NOTE_BLOCK,"Sound tools for owner and viewer");
            for(Player listener:List.of(first,viewer)) {
                for(int percent:new int[]{50,25,0,100}) {
                    call(field(games,"tools"),"cycleSound",listener);
                    String label=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(listener.getInventory().getItem(7).getItemMeta().displayName());
                    require(label.contains(percent==0 ? "음소거" : percent+"%"),"Volume item updates");
                }
            }
            Object adapter=field(games,"entities"),map=field(firstSession,"map");
            verifyAllBodies(adapter,map);
            arena(firstSession).credit(100);
            UUID[] common=new UUID[2];
            for(int i=0;i<2;i++) {
                final int index=i;
                arena(firstSession).summon(first.getUniqueId(),new SummonRoll(UnitType.SKELETON,Rarity.COMMON),(t,r,c)->{
                    try { return common[index]=(UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),t,r,c); }
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }
            double coins=arena(firstSession).coins();
            long commonCount=arena(firstSession).activeDefenders().stream().filter(d->d.rarity()==Rarity.COMMON).count();
            call(games,"toggleAutoSell",first,Rarity.COMMON);
            require(arena(firstSession).coins()==coins+commonCount*3,"Bulk sale payout");
            for(UUID id:common) require(Bukkit.getEntity(id)==null,"Bulk sale entity removal");
            require(((Set<?>)field(firstSession,"autoSell")).contains(Rarity.COMMON),"Auto-sale enabled");
            call(games,"toggleAutoSell",first,Rarity.COMMON);
            arena(firstSession).summon(first.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->{
                try{return (UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),t,r,c);}
                catch(Exception e){throw new RuntimeException(e);}
            });
            Defender sellable=arena(firstSession).defenders().getLast();call(games,"select",first,sellable.entityId());
            double beforeSale=arena(firstSession).coins();first.getInventory().setHeldItemSlot(1);
            sellClick();sellClick();
            require(arena(firstSession).coins()==beforeSale+3 && Bukkit.getEntity(sellable.entityId())==null,"Right-click tool sells once");
            first.getInventory().setHeldItemSlot(0);
            // All roles produce real combat effects while the spectator is present.
            for(UnitType type:List.of(UnitType.WOLF,UnitType.IRON_GOLEM,UnitType.SKELETON,UnitType.WITCH,UnitType.BLAZE,UnitType.EVOKER)) {
                arena(firstSession).summon(first.getUniqueId(),new SummonRoll(type,Rarity.MYTHIC),(t,r,c)->{
                    try{return (UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),t,r,c);}
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }
            call(games,"spawnEnemies",first,EnemyType.ZOMBIE,10,false);
            Defender selected=arena(firstSession).defenders().getFirst();
            selectedEntityId=Bukkit.getEntity(selected.entityId()).getEntityId();
            call(games,"select",first,selected.entityId());
            require(!Bukkit.getEntity(selected.entityId()).isGlowing(),"Selection must never change shared glow state");
            speedClick();
            require((int)call(firstSession,"speed")==2,"F GUI sets own speed");
            require((int)call(secondSession,"speed")==1,"Other session stays at one");
            firstTick=(long)field(firstSession,"simulationTick"); secondTick=(long)field(secondSession,"simulationTick");
            stage=1;return;
        }
        if (stage==1) {
            fractionalGold |= arena(firstSession).coins()!=Math.rint(arena(firstSession).coins());
            long nextFirst=(long)field(firstSession,"simulationTick"), nextSecond=(long)field(secondSession,"simulationTick");
            require(nextFirst-firstTick==expectedSpeed && nextSecond-secondTick==1,"Independent session clocks");
            firstTick=nextFirst;secondTick=nextSecond;speedChecks++;
            if(waitTicks==16) { expectedSpeed=4; speedClick(); }
            if(waitTicks==32) { expectedSpeed=8; speedClick(); }
            if(waitTicks==48) { expectedSpeed=1; speedClick(); }
            verifyAttackFacing(firstSession);
            for(Enemy enemy:arena(firstSession).activeEnemies()) {
                var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(enemy.entityId());
                require(entity.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue()==2,"Enemy scale");
                double length=arena(firstSession).grid().route().length();int side=(int)((enemy.progress()%length)/(length/4));
                float expected=new float[]{-90,0,90,-180}[side];
                require(Math.abs(Location.normalizeYaw(entity.getYaw()-expected))<.001,"Enemy route direction");
                require(Math.abs(Location.normalizeYaw(entity.getBodyYaw()-expected))<.001,"Enemy body direction");
            }
            if(waitTicks==40) {
                var unit=Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId());
                unit.setInvisible(true); // Outbound metadata must retain the owner's glow override.
            }
            if(waitTicks==60) Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()).setInvisible(false);
            if(waitTicks==80) {
                Defender unit=arena(firstSession).defenders().getLast();glowClearEntityId=Bukkit.getEntity(unit.entityId()).getEntityId();
                call(games,"select",first,unit.entityId());
            }
            if(waitTicks==100) first.hideEntity(this,Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()));
            if(waitTicks==110) first.showEntity(this,Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()));
            if(waitTicks==120) {
                menuClick(20);
                require(((Set<?>)field(firstSession,"autoSell")).contains(Rarity.COMMON),"GUI auto-sale on");
                require(arena(firstSession).activeDefenders().stream().noneMatch(d->d.rarity()==Rarity.COMMON),"Existing common units sold");
            }
            if(waitTicks==125) { menuClick(6); require((boolean)field(firstSession,"autoPlacement"),"GUI layout on"); }
            if(waitTicks==130) { arena(firstSession).credit(100); menuClick(10); require((boolean)field(firstSession,"bulkBuying"),"GUI bulk buy started"); }
            if(waitTicks==150) {
                require(!(boolean)field(firstSession,"bulkBuying"),"Batch stops at gold or slot limit");
                require((int)field(firstSession,"bulkPurchases")>0,"Batch purchased units");
                require(arena(firstSession).activeDefenders().stream().noneMatch(d->d.rarity()==Rarity.COMMON),"New common draws sold");
                require(arena(firstSession).defenders().stream().map(Defender::cell).distinct().count()==arena(firstSession).defenderCount(),"Unique layout cells");
                for(Defender d:arena(firstSession).activeDefenders()) {
                    Location actual=Bukkit.getEntity(d.entityId()).getLocation();
                    Location desired=(Location)call(field(firstSession,"map"),"location",d.position());
                    require(Math.abs(actual.getX()-desired.getX())<.001 && Math.abs(actual.getZ()-desired.getZ())<.001,"Physical layout matches combat cells");
                }
                require(((Set<?>)field(secondSession,"autoSell")).isEmpty() && !(boolean)field(secondSession,"autoPlacement"),"Automation session isolation");
            }
            if(waitTicks==155) { menuClick(6); require(!(boolean)field(firstSession,"autoPlacement"),"GUI layout off"); }
            if(waitTicks==160) { menuClick(20); require(((Set<?>)field(firstSession,"autoSell")).isEmpty(),"GUI auto-sale off"); }
            if(waitTicks==170) {
                Object map=field(firstSession,"map");
                int x=(int)call(map,"originX"), z=(int)call(map,"originZ");
                int edge=(int)call(map,"maxOffset");
                Location outside=new Location(first.getWorld(),x+edge+1.1,88,z+10,123,-24);
                Object handle=first.getClass().getMethod("getHandle").invoke(first);
                handle.getClass().getMethod("setPos",double.class,double.class,double.class).invoke(handle,outside.getX(),outside.getY(),outside.getZ());
                first.setRotation(123,-24);
                first.setAllowFlight(true); first.setFlying(true);
                edgeRecoveryExpected=new Location(first.getWorld(),x+edge+.5,88,z+10,123,-24);
            }
            if(waitTicks==171) {
                require(first.getLocation().distance(edgeRecoveryExpected)<.01,"Recover at nearest edge instead of entrance");
                require(Math.abs(first.getYaw()-123)<.01 && Math.abs(first.getPitch()+24)<.01,"Keep recovery facing");
                require(first.isFlying(),"Keep recovery flight");
            }
            if(waitTicks==180) {
                Object map=field(firstSession,"map");int x=(int)call(map,"originX"),z=(int)call(map,"originZ");
                Object handle=viewer.getClass().getMethod("getHandle").invoke(viewer);
                handle.getClass().getMethod("setPos",double.class,double.class,double.class).invoke(handle,x-6.1,90d,z+10d);
                viewer.setRotation(111,-20);viewer.setFlying(true);
                edgeRecoveryExpected=new Location(viewer.getWorld(),x-5.5,90,z+10,111,-20);
            }
            if(waitTicks==181) {
                require(viewer.getLocation().distance(edgeRecoveryExpected)<.01,"Observer recovers near edge instead of entrance");
                require(viewer.isFlying() && Math.abs(viewer.getYaw()-111)<.01,"Observer keeps flight and facing");
            }
            if (++waitTicks<340) return;
            require(arena(secondSession).enemyCount()>0,"Waves must spawn actual enemies");
            arena(firstSession).finish(Arena.Outcome.ENEMY_LIMIT); stage=2;return;
        }
        if (stage==2) {
            require(call(games,"session",first)==null,"Defeat must release session");
            require(first.getWorld().equals(world),"Defeat must return to lobby");
            require(!viewer.isInvisible(),"Observer visibility restored");
            require(viewer.getWorld().equals(world) && !(boolean)call(games,"watching",viewer),"Spectator auto return");
            require(!first.getAllowFlight() && !first.isFlying() && !viewer.getAllowFlight() && !viewer.isFlying(),"Defeat removes player and spectator flight");
            require(first.getInventory().getItem(0).getType()==Material.COMPASS,"Lobby compass restored after defeat");
            call(field(games,"tools"),"restore",first);
            require(first.getInventory().getItem(0).equals(new org.bukkit.inventory.ItemStack(Material.DIAMOND,3))
                    && first.getInventory().getItem(1).equals(new org.bukkit.inventory.ItemStack(Material.GOLD_INGOT,4)),"Original hotbar restored");
            require(call(games,"session",second)==secondSession,"Other session must continue");
            for (Defender unit:arena(firstSession).activeDefenders()) require(Bukkit.getEntity(unit.entityId())==null,"Defender cleanup");
            for (Enemy enemy:arena(firstSession).activeEnemies()) require(Bukkit.getEntity(enemy.entityId())==null,"Enemy cleanup");
            call(games,"start",first); Object restarted=call(games,"session",first);
            require(restarted!=firstSession && arena(restarted).id().equals(arena(firstSession).id()),"Arena reuse with new state");
            require((int)call(restarted,"speed")==1 && first.getAllowFlight(),"Rejoin resets speed and enables flight");
            require(((Set<?>)field(restarted,"autoSell")).isEmpty() && !(boolean)field(restarted,"autoPlacement") && !(boolean)field(restarted,"bulkBuying"),"Fresh automation controls");
            require(arena(restarted).coins()==CampaignRules.standard().startingCoins(),"Fresh funds");
            first.getInventory().setHeldItemSlot(8); sellClick();
            require(call(games,"session",first)==null,"Participant bed exits");
            call(games,"spectate",first,second.getName()); first.getInventory().setHeldItemSlot(8);
            call(games,"leave",first); require(!first.isInvisible(),"Observer exit cleanup");
            arena(secondSession).finish(Arena.Outcome.VICTORY);
            stage=3;return;
        }
        if (stage==3) {
            require(call(games,"session",first)==null && call(games,"session",second)==null,"Terminal cleanup");
            require(first.getWorld().equals(world)&&second.getWorld().equals(world),"Both back to lobby");
            require(!first.getAllowFlight() && !second.getAllowFlight(),"Timeout and victory remove flight");
            require(facedTypes==24 && checkedAttackDirections>0,"Actual entity facing must be exercised");
            require(fractionalGold,"Live fractional kill rewards");
            Files.writeString(Path.of("recovery-smoke-passed.json"),"{\"nearestEdge\":true,\"altitudePreserved\":true,\"facingPreserved\":true,\"flightPreserved\":true}");
            Files.writeString(Path.of("lobby-smoke-passed.json"),"{\"blockStates\":"+samples.size()+",\"clients\":3,\"sessionIsolation\":true,\"defeatReturn\":true,\"slotReuse\":true,\"victoryReturn\":true,\"dynamicArena\":true,\"spectatorReturn\":true,\"bulkSale\":true,\"facedMobTypes\":"+facedTypes+",\"actualAttackDirections\":"+checkedAttackDirections+",\"selectedEntityId\":"+selectedEntityId+",\"secondSelectedEntityId\":"+glowClearEntityId+"}");
            Files.writeString(Path.of("session-speed-smoke-passed.json"),"{\"clients\":3,\"mixedSpeedFrames\":"+speedChecks+",\"speeds\":[2,4,8,1],\"fGuiSpeed\":true,\"startingGold\":30,\"fractionalRewards\":"+fractionalGold+",\"oddsIcon\":true,\"saleTool\":true,\"hotbarRestored\":true,\"flightTransitions\":true,\"mobScaleTypes\":"+facedTypes+"}");
            getLogger().info("LOBBY_SMOKE_PASSED"); stage=4; Bukkit.shutdown();
            Files.writeString(Path.of("automation-smoke-passed.json"),"{\"clients\":3,\"autoSaleGui\":true,\"existingAndNewAutoSale\":true,\"bulkBuyGui\":true,\"autoPlacementGui\":true,\"physicalLayout\":true,\"sessionIsolation\":true,\"freshSessionResets\":true}");
        }
    }
    private boolean checkLobbyPortal(World world) throws Exception {
        if (portalPhase == 0) {
            portalBlock=world.getBlockAt(world.getSpawnLocation().clone().add(4,0,0));
            portalOriginal=portalBlock.getBlockData();
            portalBlock.setType(Material.NETHER_PORTAL,false);
            first.teleport(portalBlock.getLocation().add(.5,0,.5));
            portalPhase=1;return false;
        }
        if (portalPhase == 4) return true;
        if (++portalWait < 20) return false;
        portalWait=0;
        if (portalPhase == 1) {
            require(first.getLocation().distance(world.getSpawnLocation())<1,"Portal returns to spawn before menu");
            require(first.getOpenInventory().getTopInventory().getSize()==54,"Portal opens session menu");
            require(first.getOpenInventory().getTopInventory().getItem(49).getType()==Material.NETHER_STAR,"Portal join button");
            first.closeInventory();portalPhase=2;return false;
        }
        if (portalPhase == 2) {
            require(first.getOpenInventory().getTopInventory().getSize()!=54,"Standing inside does not reopen menu");
            require(first.getWorld()==world && call(games,"session",first)==null,"Portal stays in lobby without auto join");
            first.teleport(world.getSpawnLocation());portalPhase=3;return false;
        }
        portalBlock.setBlockData(portalOriginal,false);portalPhase=4;
        try { Files.writeString(Path.of("portal-smoke-passed.json"),"{\"entryOpensMenu\":true,\"noRepeatWhileInside\":true,\"staysInLobby\":true,\"joinButton\":true}"); }
        catch(java.io.IOException error) { throw new RuntimeException(error); }
        return true;
    }
    @SuppressWarnings({"deprecation","unchecked"})
    private void checkKakcInput()throws Exception {
        var kakc=Bukkit.getPluginManager().getPlugin("KAKC");
        Map<String,Integer> modes=(Map<String,Integer>)field(kakc,"changingMod");Integer previous=modes.put(first.getName(),2);
        Map<UUID,java.util.function.Consumer<String>> pending=new HashMap<>();List<String> captured=new ArrayList<>();
        Class<?> type=Class.forName("dev.moma.paper.BgmChatInput");var ctor=type.getDeclaredConstructor(java.util.function.Function.class);ctor.setAccessible(true);
        var listener=(org.bukkit.event.Listener)ctor.newInstance((java.util.function.Function<UUID,java.util.function.Consumer<String>>)pending::remove);
        Bukkit.getPluginManager().registerEvents(listener,this);
        try {
            for(String raw:List.of("https://www.youtube.com/watch?v=AbCdEf123_-&t=2","OAuth_AbCd-123")) {
                pending.put(first.getUniqueId(),captured::add);
                var event=new org.bukkit.event.player.AsyncPlayerChatEvent(false,first,raw,new HashSet<>(List.of(first,second,viewer)));
                Bukkit.getPluginManager().callEvent(event);
                require(captured.getLast().equals(raw),"KAKC raw private input preserved");
                require(event.isCancelled() && event.getMessage().isEmpty() && event.getRecipients().isEmpty(),"Private input never published");
            }
            var normal=new org.bukkit.event.player.AsyncPlayerChatEvent(false,first,"rksk",new HashSet<>(List.of(first,second)));
            Bukkit.getPluginManager().callEvent(normal);
            require(!normal.getMessage().equals("rksk"),"Ordinary KAKC conversion retained");require(modes.get(first.getName())==2,"Player KAKC mode untouched");
            Files.writeString(Path.of("kakc-input-smoke-passed.json"),"{\"rawUrlPreserved\":true,\"authCodePrivate\":true,\"normalChatConverts\":true,\"modeUnchanged\":true}");
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(listener);if(previous==null)modes.remove(first.getName());else modes.put(first.getName(),previous);
        }
    }
    private void menuClick(int slot) {
        var swap=new org.bukkit.event.player.PlayerSwapHandItemsEvent(first,first.getInventory().getItemInOffHand(),first.getInventory().getItemInMainHand());
        Bukkit.getPluginManager().callEvent(swap); require(swap.isCancelled(),"F menu");
        var click=new org.bukkit.event.inventory.InventoryClickEvent(first.getOpenInventory(),org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,slot,
                org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(click); Bukkit.getPluginManager().callEvent(click);
        require(click.isCancelled(),"Automation menu click is protected"); first.closeInventory();
    }
    private void speedClick() {
        var swap=new org.bukkit.event.player.PlayerSwapHandItemsEvent(first,first.getInventory().getItemInOffHand(),first.getInventory().getItemInMainHand());
        Bukkit.getPluginManager().callEvent(swap);require(swap.isCancelled(),"F opens GUI");
        require(first.getOpenInventory().getTopInventory().getItem(8).getType()==Material.CLOCK,"Speed clock in F GUI");
        var odds=first.getOpenInventory().getTopInventory().getItem(0);
        require(odds.getType()==Material.KNOWLEDGE_BOOK && odds.getItemMeta().lore().size()>=9,"Summon odds icon");
        require(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(odds.getItemMeta().lore().get(8)).contains("0.019%"),"Exact Primordial probability");
        var click=new org.bukkit.event.inventory.InventoryClickEvent(first.getOpenInventory(),org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,8,
                org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(click);Bukkit.getPluginManager().callEvent(click);
        require(click.isCancelled(),"GUI item cannot be taken");first.closeInventory();
    }
    private void sellClick() {
        var event=new org.bukkit.event.player.PlayerInteractEvent(first,org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                first.getInventory().getItemInMainHand(),null,org.bukkit.block.BlockFace.SELF,org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
    }
    private void verifyAllBodies(Object adapter,Object map) throws Exception {
        Class<?> effectsClass = Class.forName("dev.moma.paper.AttackEffects");
        Method attackSound = effectsClass.getDeclaredMethod("attackSound", UnitType.class);
        attackSound.setAccessible(true);
        for(UnitType type:UnitType.values()) {
            Object sound = attackSound.invoke(null, type);
            String soundKey = (String)call(sound, "key");
            require(Registry.SOUNDS.get(NamespacedKey.fromString(soundKey)) != null, "Registered attack sound " + type + " " + soundKey);
            require(type == UnitType.WOLF || !soundKey.contains("wolf"), "Wolf sound only for wolf");
            UUID id=(UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),type,Rarity.COMMON,new Cell(0,0));
            Defender defender=new Defender(id,first.getUniqueId(),arena(firstSession).id(),type,Rarity.COMMON,new Cell(0,0));
            var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(id);
            double scale=Set.of(UnitType.GHAST,UnitType.WARDEN,UnitType.IRON_GOLEM,UnitType.RAVAGER,UnitType.HOGLIN,UnitType.POLAR_BEAR,UnitType.PANDA).contains(type)?1:2;
            require(entity.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue()==scale,"Defender scale "+type);
            call(adapter,"face",defender,new Point(3,0));
            require((boolean)call(adapter,"moveDefender",id,call(map,"location",defender.position())),"Anchor "+type);
            require(Math.abs(entity.getYaw()+90)<.001 && Math.abs(entity.getBodyYaw()+90)<.001,"Facing survives anchor "+type);
            Location moved=(Location)call(map,"location",new Point(3,3));
            require((boolean)call(adapter,"moveDefender",id,moved),"Manual move "+type);
            require(Math.abs(entity.getYaw()+90)<.001,"Facing survives movement "+type);
            call(adapter,"remove",id);facedTypes++;
        }
    }
    private void verifyAttackFacing(Object session) throws Exception {
        var attacks=(Map<?,?>)field(field(session,"attackEffects"),"attacks");
        for(var attack:attacks.entrySet()) {
            Defender defender=(Defender)attack.getKey();Point primary=(Point)((List<?>)attack.getValue()).getFirst();
            Point origin=defender.position();if(origin.equals(primary))continue;
            float yaw=Location.normalizeYaw((float)Math.toDegrees(Math.atan2(-(primary.x()-origin.x()),primary.z()-origin.z())));
            var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(defender.entityId());
            require(entity!=null && Math.abs(Location.normalizeYaw(entity.getYaw()-yaw))<.001,"Attack target head direction");
            require(Math.abs(Location.normalizeYaw(entity.getBodyYaw()-yaw))<.001,"Attack target body direction");
            checkedAttackDirections++;
        }
    }
    private static Arena arena(Object session) throws Exception { return (Arena)field(session,"arena"); }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object target,String name) throws Exception { Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target); }
    private static Object call(Object target,String name,Object...args) throws Exception {
        for (Method m:target.getClass().getDeclaredMethods()) {
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
            Class<?>[] types=m.getParameterTypes();boolean matches=true;
            for(int i=0;i<args.length;i++) {
                Class<?> type=types[i]==int.class?Integer.class:types[i]==boolean.class?Boolean.class:types[i];
                if(args[i]!=null&&!type.isInstance(args[i]))matches=false;
            }
            if(matches){m.setAccessible(true);return m.invoke(target,args);}
        }
        throw new NoSuchMethodException(name);
    }
}
