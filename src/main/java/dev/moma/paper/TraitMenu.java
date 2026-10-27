package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataContainer;

final class TraitMenu implements Listener {
    private final MomaPlugin plugin;
    private final GameService games;
    private final Lobby lobby;
    private static final int PAGE_SIZE=36;
    private record Group(TraitCatalog.Family family,AttackRole role) {
        List<TraitCatalog.Entry> entries() {
            return TraitCatalog.ALL.stream().filter(e->e.family()==family && e.role()==role).toList();
        }
        String name() {
            return switch(family) {
                case START_GOLD -> "출전 수당";
                case FIRST_PURCHASE -> "첫 구매 승급";
                case OPENING_ODDS -> "다가오는 인연";
                case DAMAGE -> "수비 교본";
                case NORMAL_DAMAGE -> "정예 소탕";
                case SPEED -> "신속한 지휘";
                case BOSS_DAMAGE -> "거인 사냥";
                case CRITICAL -> "초월의 일격";
                case ROLE_DAMAGE -> role.label()+" 교본";
                case ENHANCEMENT -> "담금질";
                case SPENDING_DAMAGE,GOLD_INCOME -> "전장의 투자";
                case DUPLICATE_ODDS -> "전우의 재회";
            };
        }
    }
    private static final List<Group> GROUPS=TraitCatalog.ALL.stream().map(e->new Group(e.family(),e.role())).distinct().toList();
    private static final class Holder implements InventoryHolder {
        final UUID owner;final int page;final Group group;final List<TraitCatalog.Entry> entries;
        Inventory inventory;
        Holder(UUID owner,int page,Group group,List<TraitCatalog.Entry> entries){this.owner=owner;this.page=page;this.group=group;this.entries=entries;}
        int size(){return group==null?GROUPS.size():group.entries().size();}
        @Override public Inventory getInventory(){return inventory;}
    }
    private static final class ChallengeHolder implements InventoryHolder {
        final UUID owner;Inventory inventory;boolean consumed;
        ChallengeHolder(UUID owner){this.owner=owner;}
        @Override public Inventory getInventory(){return inventory;}
    }
    TraitMenu(MomaPlugin plugin,GameService games,Lobby lobby){this.plugin=plugin;this.games=games;this.lobby=lobby;}
    private boolean eligible(Player player) {
        return !games.active(player) && player.hasPermission("moma.play")
                && (games.quizCabinOwner(player)||games.mining!=null&&games.mining.owns(player)||lobby!=null&&lobby.contains(player.getLocation()));
    }
    void open(Player player){open(player,null,0);}
    private void open(Player player,Group group,int requested) {
        if(!eligible(player))return;
        List<TraitCatalog.Entry> entries=group==null?List.of():group.entries();
        int size=group==null?GROUPS.size():entries.size(),pages=(size+PAGE_SIZE-1)/PAGE_SIZE,page=Math.clamp(requested,0,pages-1);
        var holder=new Holder(player.getUniqueId(),page,group,group==null?List.of():entries.subList(page*PAGE_SIZE,Math.min((page+1)*PAGE_SIZE,size)));
        holder.inventory=Bukkit.createInventory(holder,54,Ui.text("&d"+(group==null?"특성 선택":group.name())+" &8· "+(page+1)+"/"+pages));
        var data=player.getPersistentDataContainer();var selected=TraitSelections.load(data);
        if(group==null)for(int i=page*PAGE_SIZE;i<Math.min((page+1)*PAGE_SIZE,size);i++)
            holder.inventory.setItem(i-page*PAGE_SIZE,groupItem(GROUPS.get(i),data,selected));
        else for(int i=0;i<holder.entries.size();i++)holder.inventory.setItem(i,traitItem(holder.entries.get(i),data,selected));
        int slots=TraitSelections.slots(data);
        for(int i=0;i<4;i++) {
            var entry=i<selected.entries().size()?selected.entries().get(i):null;
            holder.inventory.setItem(45+i,Ui.item(i>=slots?Material.BARRIER:entry==null?Material.LIGHT_GRAY_DYE:Material.NETHER_STAR,
                    "&e특성 "+(i+1)+" &7· "+(i>=slots?"잠김":entry==null?"빈 슬롯":entry.name()),
                    i>=slots?"&7"+(i==3?"시즌 1에서 ":"")+new int[]{100,250,500,1500}[i]+"라운드 도달 시 해금":entry==null?"&7위 특성책에서 선택하세요.":"&7클릭: 해제"));
        }
        holder.inventory.setItem(49,Ui.item(Material.PAPER,"&d장착 "+selected.entries().size()+"/"+slots,
                "&7같은 계열은 하나만 장착","&7선택한 특성은 다음 게임 시작부터 적용"));
        holder.inventory.setItem(50,Ui.item(Material.TARGET,"&e챌린지 도전","&7소수 정예 도전과 상위 특성 조건 확인"));
        if(page>0)holder.inventory.setItem(51,Ui.item(Material.ARROW,"&e이전 페이지"));
        if(group!=null)holder.inventory.setItem(52,Ui.item(Material.ARROW,"&e특성책 목록으로 돌아가기"));
        if(page+1<pages)holder.inventory.setItem(53,Ui.item(Material.ARROW,"&e다음 페이지"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    private ItemStack groupItem(Group group,PersistentDataContainer data,TraitLoadout selected) {
        var entries=group.entries();var lore=new ArrayList<String>();
        entries.stream().map(TraitCatalog.Entry::description).distinct().forEach(effect->lore.add("&f"+effect));
        long unlocked=entries.stream().filter(e->TraitSelections.unlocked(data,e)).count();
        lore.add("&7해금: "+unlocked+"/"+entries.size());
        var equipped=selected.allEntries().stream().filter(entries::contains).toList();
        for(var entry:equipped)lore.add("&a"+(entry.passive()?"적용: ":"장착: ")+entry.name());
        if(entries.getFirst().passive())lore.add("&7패시브 · 해금한 효과 자동 적용");
        lore.add("&e클릭: 특성 목록 보기");
        return Ui.item(Material.ENCHANTED_BOOK,(equipped.isEmpty()?unlocked>0?"&d":"&7":"&a")+group.name(),lore.toArray(String[]::new));
    }
    private ItemStack traitItem(TraitCatalog.Entry entry,PersistentDataContainer data,TraitLoadout selected) {
        boolean unlocked=TraitSelections.unlocked(data,entry),equipped=selected.ids().contains(entry.id());
        var lore=new ArrayList<String>();lore.add("&f"+entry.description());
        lore.add("&7업적: "+entry.achievement().title());lore.add("&7"+entry.achievement().description());
        lore.add("&7진행: "+AchievementStats.get(data,entry.achievement().metric())+"/"+entry.achievement().target());
        if(entry.passive()) {
            boolean active=selected.passives().stream().anyMatch(e->e.id().equals(entry.id()));
            lore.add(!unlocked?"&c업적 달성 시 해금":active?"&a패시브 · 자동 적용":"&7상위 패시브 적용 중");
        } else lore.add(equipped?"&e클릭: 해제":unlocked?"&a클릭: 장착":"&c업적 달성 시 해금");
        return Ui.item(!unlocked?Material.GRAY_DYE:equipped?Material.LIME_DYE:Material.ENCHANTED_BOOK,
                (equipped?"&a":unlocked?"&d":"&7")+entry.name(),lore.toArray(String[]::new));
    }
    private void challenges(Player player) {
        var holder=new ChallengeHolder(player.getUniqueId());
        holder.inventory=Bukkit.createInventory(holder,27,Ui.text("&e챌린지 도전"));
        holder.inventory.setItem(11,Ui.item(Material.TARGET,"&e소수 정예 도전",
                "&f게임 시작부터 배치 최대 10마리","&7대기석 48마리 · 합성과 자리 교체 가능",
                "&7500 / 600 / 700 / 800라운드 도달","&a일반 적 피해 +12 / 14 / 16 / 18%",
                "&e클릭: 현재 특성으로 도전 시작"));
        holder.inventory.setItem(13,Ui.item(Material.GOLD_INGOT,"&6제한 예산",
                "&f일반 게임에서도 자동으로 도전합니다.","&7500 / 600 / 700 / 800라운드 도달",
                "&7총 소환 비용 150만 / 200만 / 250만 / 300만 이하",
                "&7판매 환급으로 사용액이 줄어들지 않습니다.","&a적 처치 골드 +10 / 12 / 15 / 18%"));
        holder.inventory.setItem(15,Ui.item(Material.CLOCK,"&b신속한 정리",
                "&f일반 게임에서도 자동으로 도전합니다.","&7500라운드부터 마지막 적 등장 후 5초 안에 전멸",
                "&7게임 시간 기준 · 실패 시 연속 횟수 초기화","&730 / 60 / 90 / 120 / 150라운드 연속",
                "&a공격속도 +10 / 12 / 14 / 16 / 18%"));
        holder.inventory.setItem(22,Ui.item(Material.ARROW,"&e특성책으로 돌아가기"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        Player player=event.getPlayer();
        if(eligible(player) && games.tools.holding(player,"traits")){event.setCancelled(true);open(player);}
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof ChallengeHolder holder) {
            event.setCancelled(true);
            if(!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                    || !eligible(player) || holder.consumed || event.getClick()!=ClickType.LEFT || player.getOpenInventory().getTopInventory()!=holder.inventory)return;
            if(event.getRawSlot()==22){open(player);return;}
            if(event.getRawSlot()!=11)return;
            holder.consumed=true;
            Bukkit.getScheduler().runTask(plugin,()->{
                if(!player.isOnline() || !eligible(player) || player.getOpenInventory().getTopInventory()!=holder.inventory)return;
                player.closeInventory();
                try { games.start(player,true); }
                catch(IllegalArgumentException | IllegalStateException error){player.sendMessage(Ui.text("&c"+error.getMessage()));Ui.sound(player,Ui.Cue.ERROR);}
            });
            return;
        }
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !eligible(player) || event.getClick()!=ClickType.LEFT)return;
        // A queued click from an inventory that was already replaced must not toggle twice.
        if(player.getOpenInventory().getTopInventory()!=holder.inventory)return;
        int slot=event.getRawSlot();
        if(slot==50){challenges(player);return;}
        if(slot==51 && holder.page>0){open(player,holder.group,holder.page-1);return;}
        if(slot==52 && holder.group!=null){open(player,null,GROUPS.indexOf(holder.group)/PAGE_SIZE);return;}
        if(slot==53 && (holder.page+1)*PAGE_SIZE<holder.size()){open(player,holder.group,holder.page+1);return;}
        if(holder.group==null && slot>=0 && slot<PAGE_SIZE && holder.page*PAGE_SIZE+slot<GROUPS.size()) {
            open(player,GROUPS.get(holder.page*PAGE_SIZE+slot),0);return;
        }
        var data=player.getPersistentDataContainer();var ids=new ArrayList<>(TraitSelections.load(data).ids());
        try {
            if(slot>=0 && slot<holder.entries.size()) {
                var entry=holder.entries.get(slot);
                if(!TraitSelections.unlocked(data,entry))throw new IllegalArgumentException("업적을 먼저 달성하세요.");
                if(entry.passive()){Ui.sound(player,Ui.Cue.CLICK);open(player,holder.group,holder.page);return;}
                if(!ids.remove(entry.id())) {
                    ids.removeIf(id->TraitCatalog.find(id).family()==entry.family());ids.add(entry.id());
                }
            } else if(slot>=45 && slot<49 && slot-45<ids.size())ids.remove(slot-45);
            else return;
            TraitSelections.save(data,ids);Ui.sound(player,Ui.Cue.CLICK);open(player,holder.group,holder.page);
        } catch(IllegalArgumentException error){player.sendMessage(Ui.text("&c"+error.getMessage()));Ui.sound(player,Ui.Cue.ERROR);}
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder
                || event.getView().getTopInventory().getHolder() instanceof ChallengeHolder)event.setCancelled(true);
    }
}
