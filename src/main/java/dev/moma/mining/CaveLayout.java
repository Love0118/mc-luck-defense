package dev.moma.mining;

import java.util.*;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** Seeded three-dimensional caves around a timbered haulage tunnel. Only infrastructure is protected. */
final class CaveLayout {
    static final String VERSION="noise-rails-v2";
    static final int RADIUS=36,FLOOR=12,ROOF=76,HUB_FLOOR=56;
    record Cell(int x,int y,int z) {
        List<Cell> neighbors(){return List.of(new Cell(x+1,y,z),new Cell(x-1,y,z),new Cell(x,y+1,z),new Cell(x,y-1,z),new Cell(x,y,z+1),new Cell(x,y,z-1));}
    }
    private static final Map<Cell,String> STRUCTURES=structures();
    private static final class Cells { static final List<Cell> ALL=createCells(); }
    static boolean inside(Cell c){return Math.abs(c.x)<=RADIUS&&Math.abs(c.z)<=RADIUS&&c.y>=FLOOR&&c.y<=ROOF;}
    static boolean hub(Cell c){return Math.abs(c.x)<=7&&c.z>=-34&&c.z<=-24&&c.y>=HUB_FLOOR-2&&c.y<=HUB_FLOOR+7;}
    static boolean route(Cell c){return c.y>=HUB_FLOOR-1&&c.y<=HUB_FLOOR+7&&
            (Math.abs(c.x)<=3&&c.z>=-24&&c.z<=27||Math.abs(c.z-4)<=3&&Math.abs(c.x)<=27);}
    static boolean air(Cell c){
        if(c.y<=FLOOR||c.y>=ROOF)return false;
        if((hub(c)||route(c))&&c.y>HUB_FLOOR&&c.y<=HUB_FLOOR+6)return true;
        double n=noise(c.x/18.0,c.y/12.0,c.z/18.0)+.42*noise(c.x/8.0+17,c.y/7.0,c.z/8.0-23);
        double chamber=Math.pow(c.x/26.0,2)+Math.pow(c.z/26.0,2)+Math.pow((c.y-48)/21.0,2);
        return n>.24&&chamber<1.8 || Math.pow((c.x-9*Math.sin(c.z*.11))/3.4,2)+Math.pow((c.y-(43+8*Math.sin(c.z*.07)))/3.6,2)<1&&Math.abs(c.z)<30;
    }
    static boolean mineable(Cell c){return inside(c)&&Math.abs(c.x)<RADIUS&&Math.abs(c.z)<RADIUS&&c.y>FLOOR&&c.y<ROOF
            &&!hub(c)&&!route(c)&&!air(c)&&!STRUCTURES.containsKey(c);}
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
    private static Map<Cell,String> structures(){
        var map=new HashMap<Cell,String>();
        // Ballast, wooden sleepers and a continuous main rail, with a working siding.
        for(int z=-32;z<=27;z++)for(int x=-2;x<=2;x++){
            map.put(new Cell(x,HUB_FLOOR,z),x==0&&Math.floorMod(z,3)==0?"dark_oak_planks":Math.floorMod(x+z,5)==0?"coarse_dirt":"gravel");
            if(x==0)map.put(new Cell(x,HUB_FLOOR+1,z),"rail[shape=north_south]");
        }
        for(int x=-27;x<=27;x++)for(int z=2;z<=6;z++){
            map.put(new Cell(x,HUB_FLOOR,z),Math.floorMod(x,3)==0?"dark_oak_planks":"gravel");
            if(z==4&&x!=0)map.put(new Cell(x,HUB_FLOOR+1,z),"rail[shape=east_west]");
        }
        map.put(new Cell(0,HUB_FLOOR+1,4),"rail[shape=north_east]");
        for(int z=-23;z<=26;z+=7){frame(map,0,z,false);bridgeLeg(map,2,z);bridgeLeg(map,-2,z);}
        for(int x=-24;x<=24;x+=8)if(Math.abs(x)>4){frame(map,x,4,true);bridgeLeg(map,x,2);bridgeLeg(map,x,6);}
        for(int x=-7;x<=7;x++)for(int z=-34;z<=-24;z++)map.put(new Cell(x,HUB_FLOOR,z),"dark_oak_planks");
        for(int x:new int[]{-7,7})for(int z:new int[]{-33,-25})for(int y=HUB_FLOOR+1;y<=HUB_FLOOR+6;y++)map.put(new Cell(x,y,z),"stripped_spruce_log[axis=y]");
        for(int x=-7;x<=7;x++)for(int z:new int[]{-33,-25})map.put(new Cell(x,HUB_FLOOR+7,z),"dark_oak_planks");
        for(int x:new int[]{-5,5})map.put(new Cell(x,HUB_FLOOR+6,-29),"lantern[hanging=true]");
        for(int z=-31;z<=-28;z++){map.put(new Cell(6,HUB_FLOOR+1,z),"barrel[facing=up]");map.put(new Cell(-6,HUB_FLOOR+1,z),"spruce_planks");}
        map.put(new Cell(0,HUB_FLOOR+1,-34),"lodestone");
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
            if (!occupied.test(c) && (material.equals("AIR") || material.equals("STONE"))) {
                next.put(c, "STONE"); stone.add(c);
            }
        });
        next.putAll(veins(stone, random, .22));
        return next;
    }
}
