package dev.moma.mining;

import java.util.*;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** Seeded three-dimensional caves around a timbered haulage tunnel. Only infrastructure is protected. */
final class CaveLayout {
    static final String VERSION="shallow-lit-terraces-v3";
    static final int RADIUS=24,FLOOR=40,ROOF=68,HUB_FLOOR=56;
    static final List<Cell> SHOPS=List.of(new Cell(4,57,-20),new Cell(-17,57,4),
            new Cell(4,51,-12),new Cell(17,51,4),new Cell(4,45,12),new Cell(-17,45,4));
    record Cell(int x,int y,int z) {
        List<Cell> neighbors(){return List.of(new Cell(x+1,y,z),new Cell(x-1,y,z),new Cell(x,y+1,z),new Cell(x,y-1,z),new Cell(x,y,z+1),new Cell(x,y,z-1));}
    }
    private static final Map<Cell,String> STRUCTURES=structures();
    private static final class Cells { static final List<Cell> ALL=createCells(); }
    static boolean inside(Cell c){return Math.abs(c.x)<=RADIUS&&Math.abs(c.z)<=RADIUS&&c.y>=FLOOR&&c.y<=ROOF;}
    static boolean hub(Cell c){return Math.abs(c.x)<=7&&c.z>=-22&&c.z<=-16&&c.y>=HUB_FLOOR-2&&c.y<=HUB_FLOOR+7;}
    static boolean route(Cell c){return c.y>=HUB_FLOOR-1&&c.y<=HUB_FLOOR+7&&
            (Math.abs(c.x)<=3&&c.z>=-16&&c.z<=20||Math.abs(c.z-4)<=3&&Math.abs(c.x)<=20);}
    static boolean terrace(Cell c){return c.y>=44&&c.y<=55&&
            (Math.abs(c.x)<=3&&c.z>=-15&&c.z<=17||Math.abs(c.z-4)<=3&&Math.abs(c.x)<=20);}
    static boolean air(Cell c){
        if(c.y<=FLOOR||c.y>=ROOF)return false;
        if((hub(c)||route(c))&&c.y>HUB_FLOOR&&c.y<=HUB_FLOOR+6)return true;
        if(terrace(c)&&Math.floorMod(c.y-44,6)>0&&Math.floorMod(c.y-44,6)<=4)return true;
        double n=noise(c.x/12.0,c.y/7.0,c.z/12.0)+.42*noise(c.x/6.0+17,c.y/4.0,c.z/6.0-23);
        double chamber=Math.pow(c.x/19.0,2)+Math.pow(c.z/19.0,2)+Math.pow((c.y-52)/11.0,2);
        return n>.12&&chamber<1.7 || Math.pow((c.x-6*Math.sin(c.z*.16))/3.1,2)+Math.pow((c.y-(49+3*Math.sin(c.z*.11)))/3.2,2)<1&&Math.abs(c.z)<20;
    }
    static boolean mineable(Cell c){return inside(c)&&Math.abs(c.x)<RADIUS&&Math.abs(c.z)<RADIUS&&c.y>FLOOR&&c.y<ROOF
            &&!hub(c)&&!route(c)&&!terrace(c)&&!air(c)&&!STRUCTURES.containsKey(c);}
    static List<Cell> cells(){return Cells.ALL;}
    private static List<Cell> createCells(){var result=new ArrayList<Cell>();for(int x=-RADIUS+1;x<RADIUS;x++)for(int z=-RADIUS+1;z<RADIUS;z++)for(int y=FLOOR+1;y<ROOF;y++){
        var c=new Cell(x,y,z);if(mineable(c))result.add(c);
    }return List.copyOf(result);}
    static String shell(Cell c){
        if(Math.abs(c.x)==RADIUS||Math.abs(c.z)==RADIUS||c.y==FLOOR||c.y==ROOF)return "BEDROCK";
        String structure=STRUCTURES.get(c);if(structure!=null){int state=structure.indexOf('[');return (state<0?structure:structure.substring(0,state)).toUpperCase(Locale.ROOT);}
        if((hub(c)||route(c))&&c.y<=HUB_FLOOR)return "STONE";
        return air(c)?"AIR":"STONE";
    }
    static String blockData(Cell c){var value=STRUCTURES.get(c);return value==null?null:"minecraft:"+value;}
    /** Extra clearance for a standing player's 1.8-block body while stepping up a half-height stair. */
    static List<Cell> stairClearance(){
        var cells=new ArrayList<Cell>();
        for(int base:new int[]{44,50})for(int x:new int[]{-14,10}){
            for(int step=0;step<6;step++)cells.add(new Cell(x+step,base+step+4,7));
            for(int head=1;head<=3;head++)cells.add(new Cell(x+6,base+6+head,7));
        }
        return List.copyOf(cells);
    }
    private static Map<Cell,String> structures(){
        var map=new HashMap<Cell,String>();
        // Ballast, wooden sleepers and a continuous main rail, with a working siding.
        for(int z=-20;z<=20;z++)for(int x=-2;x<=2;x++){
            map.put(new Cell(x,HUB_FLOOR,z),x==0&&Math.floorMod(z,3)==0?"dark_oak_planks":Math.floorMod(x+z,5)==0?"coarse_dirt":"gravel");
            if(x==0)map.put(new Cell(x,HUB_FLOOR+1,z),"rail[shape=north_south]");
        }
        for(int x=-20;x<=20;x++)for(int z=2;z<=6;z++){
            map.put(new Cell(x,HUB_FLOOR,z),Math.floorMod(x,3)==0?"dark_oak_planks":"gravel");
            if(z==4&&x!=0)map.put(new Cell(x,HUB_FLOOR+1,z),"rail[shape=east_west]");
        }
        map.put(new Cell(0,HUB_FLOOR+1,4),"rail[shape=north_east]");
        for(int z=-15;z<=20;z+=7){frame(map,0,z,false);bridgeLeg(map,2,z);bridgeLeg(map,-2,z);}
        for(int x=-16;x<=16;x+=8)if(Math.abs(x)>4){frame(map,x,4,true);bridgeLeg(map,x,2);bridgeLeg(map,x,6);}
        for(int x=-7;x<=7;x++)for(int z=-22;z<=-16;z++)map.put(new Cell(x,HUB_FLOOR,z),"dark_oak_planks");
        for(int x:new int[]{-7,7})for(int z:new int[]{-21,-17})for(int y=HUB_FLOOR+1;y<=HUB_FLOOR+6;y++)map.put(new Cell(x,y,z),"stripped_spruce_log[axis=y]");
        for(int x=-7;x<=7;x++)for(int z:new int[]{-21,-17})map.put(new Cell(x,HUB_FLOOR+7,z),"dark_oak_planks");
        for(int x:new int[]{-5,5})map.put(new Cell(x,HUB_FLOOR+6,-19),"lantern[hanging=true]");
        map.put(new Cell(0,HUB_FLOOR+1,-22),"lodestone");
        for(int floor:new int[]{44,50}){
            for(int x=-20;x<=20;x++)for(int z=2;z<=6;z++)map.put(new Cell(x,floor,z),"spruce_planks");
            for(int z=-15;z<=17;z++)for(int x=-2;x<=2;x++)map.put(new Cell(x,floor,z),"spruce_planks");
        }
        // Repeated ladder shafts and stairs remain climbable after nearby rock is mined out.
        for(int z:new int[]{-12,12})for(int y=44;y<=56;y++){
            map.put(new Cell(-3,y,z),"stripped_spruce_log[axis=y]");
            map.put(new Cell(-2,y,z),"ladder[facing=east]");
            map.put(new Cell(-2,y,z+1),"air");
        }
        for(int base:new int[]{44,50})for(int x:new int[]{-14,10})for(int step=0;step<6;step++){
            map.put(new Cell(x+step,base+step+1,7),"spruce_stairs[facing=east]");
            for(int head=2;head<=3;head++)map.put(new Cell(x+step,base+step+head,7),"air");
            map.put(new Cell(x+6,base+6,7),"spruce_planks");
        }
        for(var shop:SHOPS){
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                map.put(new Cell(shop.x+dx,shop.y-1,shop.z+dz),"spruce_planks");
                for(int dy=0;dy<=2;dy++)map.put(new Cell(shop.x+dx,shop.y+dy,shop.z+dz),"air");
            }
            map.put(new Cell(shop.x+1,shop.y,shop.z+1),"barrel[facing=up]");
            map.put(new Cell(shop.x+1,shop.y+1,shop.z+1),"lantern");
        }
        // Soft fill light in natural air pockets, plus visible lanterns along all three levels.
        for(int x=-18;x<=18;x+=6)for(int z=-18;z<=18;z+=6)for(int y=43;y<=65;y+=4){var c=new Cell(x,y,z);if(air(c)&&!map.containsKey(c))map.put(c,"light[level=13]");}
        for(int floor:new int[]{44,50,56})for(int x=-18;x<=18;x+=6){
            if(Math.abs(x)<=3)continue;
            map.put(new Cell(x,floor+1,6),"spruce_fence");map.put(new Cell(x,floor+2,6),"lantern");
        }
        for(var cell:stairClearance())map.put(cell,"air");
        return Map.copyOf(map);
    }
    private static void frame(Map<Cell,String> map,int x,int z,boolean across){
        for(int side:new int[]{-3,3})for(int y=HUB_FLOOR+1;y<=HUB_FLOOR+5;y++)map.put(new Cell(x+(across?0:side),y,z+(across?side:0)),"stripped_spruce_log[axis=y]");
        for(int span=-3;span<=3;span++)map.put(new Cell(x+(across?0:span),HUB_FLOOR+6,z+(across?span:0)),"dark_oak_planks");
        map.put(new Cell(x+(across?0:2),HUB_FLOOR+5,z+(across?2:0)),"lantern[hanging=true]");
    }
    private static void bridgeLeg(Map<Cell,String> map,int x,int z){
        for(int y=HUB_FLOOR-1;y>FLOOR;y--){var cell=new Cell(x,y,z);if(!air(cell))break;map.put(cell,"stripped_spruce_log[axis=y]");}
    }
    private static double noise(double x,double y,double z){
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y),iz=(int)Math.floor(z);double fx=fade(x-ix),fy=fade(y-iy),fz=fade(z-iz);
        double a=lerp(hash(ix,iy,iz),hash(ix+1,iy,iz),fx),b=lerp(hash(ix,iy+1,iz),hash(ix+1,iy+1,iz),fx);
        double c=lerp(hash(ix,iy,iz+1),hash(ix+1,iy,iz+1),fx),d=lerp(hash(ix,iy+1,iz+1),hash(ix+1,iy+1,iz+1),fx);
        return lerp(lerp(a,b,fy),lerp(c,d,fy),fz);
    }
    private static double hash(int x,int y,int z){long h=x*0x632be59bd9b4e019L^y*0x9e3779b97f4a7c15L^z*0x85157af5L^20261025L;h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return ((h^(h>>>31))>>>11)*0x1.0p-52-1;}
    private static double fade(double v){return v*v*v*(v*(v*6-15)+10);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    /** Each emitted vein is face-connected and has at least three blocks. No isolated sprinkles. */
    static Map<Cell, String> veins(Collection<Cell> eligible, RandomGenerator random, double fraction) {
        var available = new HashSet<>(eligible);
        var seeds = new ArrayList<>(eligible);
        Collections.shuffle(seeds, new Random(random.nextLong()));
        var result = new LinkedHashMap<Cell, String>();
        int target = (int) (eligible.size() * Math.clamp(fraction, .05, .5));
        for (Cell seed : seeds) {
            if (result.size() >= target) break;
            if (!available.contains(seed)) continue;
            int size = random.nextInt(5, 12);
            var vein = new LinkedHashSet<Cell>();
            var frontier = new ArrayList<Cell>(); frontier.add(seed);
            while (!frontier.isEmpty() && vein.size() < size) {
                Cell next = frontier.remove(random.nextInt(frontier.size()));
                if (!available.contains(next) || !vein.add(next)) continue;
                for (Cell neighbor : next.neighbors()) if (available.contains(neighbor) && !vein.contains(neighbor)) frontier.add(neighbor);
            }
            if (vein.size() < 3) continue;
            String ore = MiningRules.randomOre(random).block();
            for (Cell c : vein) { available.remove(c); result.put(c, ore); }
        }
        return result;
    }
    static Map<Cell, String> refill(Map<Cell, String> current, Predicate<Cell> occupied, RandomGenerator random) {
        var next = new LinkedHashMap<>(current);
        var stone = new ArrayList<Cell>();
        current.forEach((c, material) -> {
            if (!occupied.test(c)) {
                next.put(c, "STONE"); stone.add(c);
            }
        });
        next.putAll(veins(stone, random, .22));
        return next;
    }
}
