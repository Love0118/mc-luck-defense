package dev.moma.mining;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One FULL-synchronous SQLite commit owns both a cell claim and its player's cargo. */
final class MiningStore implements AutoCloseable {
    enum Operation { SPEED, FORTUNE, REFUND, SALE }
    record Pending(String id, Operation operation, long amount,MiningRules.Outcome outcome,int gain) {
        Pending(String id,Operation operation,long amount){this(id,operation,amount,MiningRules.Outcome.SUCCESS,1);}
        Pending { Objects.requireNonNull(id); Objects.requireNonNull(operation);Objects.requireNonNull(outcome); if (amount < 1||gain<0||gain>25) throw new IllegalArgumentException("Invalid amount or upgrade gain"); }
    }
    record Recovery(UUID token, byte[] data) {
        Recovery { Objects.requireNonNull(token); data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    record Profile(UUID tool, int speed, int fortune, Map<MiningRules.Ore, Long> cargo, Recovery recovery, Pending pending) {
        Profile {
            Objects.requireNonNull(tool);
            MiningRules.speed(speed);
            if (fortune < 0 || fortune > MiningRules.FORTUNE_CAP) throw new IllegalArgumentException("Invalid fortune");
            for (long count : cargo.values()) if (count < 0) throw new IllegalArgumentException("Negative cargo");
            cargo = Map.copyOf(cargo);
        }
        static Profile fresh() { return new Profile(UUID.randomUUID(), 50, 0, Map.of(), null, null); }
        Profile recovery(Recovery value) { return new Profile(tool, speed, fortune, cargo, value, pending); }
        Profile pending(Pending value) { return new Profile(tool, speed, fortune, cargo, recovery, value); }
        Profile upgrade(Pending attempt) {
            if(attempt.outcome()==MiningRules.Outcome.DESTROYED)return new Profile(UUID.randomUUID(),50,0,cargo,recovery,null);
            int gain=attempt.outcome()==MiningRules.Outcome.SUCCESS?attempt.gain():0;
            return new Profile(tool, Math.min(MiningRules.SPEED_CAP,speed+(attempt.operation()==Operation.SPEED?gain:0)),
                    Math.min(3,fortune+(attempt.operation()==Operation.FORTUNE?gain:0)),cargo,recovery,null);
        }
        Profile emptyCargo(Pending payout) { return new Profile(tool, speed, fortune, Map.of(), recovery, payout); }
        Profile credit(MiningRules.Ore ore, int amount) {
            var next = new EnumMap<MiningRules.Ore,Long>(MiningRules.Ore.class); next.putAll(cargo);
            next.merge(ore, (long) amount, Math::addExact);
            return new Profile(tool, speed, fortune, next, recovery, pending);
        }
    }
    private final Connection connection;
    MiningStore(Path file) throws IOException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=3000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
                try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                    if (r.next() && r.getInt(1) > 1) throw new SQLException("Unsupported mining database version");
                }
                s.execute("CREATE TABLE IF NOT EXISTS mining_profiles (id TEXT PRIMARY KEY, data BLOB NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS mining_cells (x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT NULL,PRIMARY KEY(x,y,z))");
                s.execute("CREATE TABLE IF NOT EXISTS mining_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                s.execute("PRAGMA user_version=1");
            }
        } catch (SQLException e) { throw io(e); }
    }
    Profile profile(UUID id) throws IOException {
        try (var q = connection.prepareStatement("SELECT data FROM mining_profiles WHERE id=?")) {
            q.setString(1, id.toString());
            try (var r = q.executeQuery()) { return r.next() ? decode(r.getBytes(1)) : Profile.fresh(); }
        } catch (SQLException e) { throw io(e); }
    }
    void save(UUID id, Profile profile) throws IOException { transaction(() -> writeProfile(id, profile)); }
    List<UUID> players()throws IOException{
        var ids=new ArrayList<UUID>();try(var q=connection.createStatement();var r=q.executeQuery("SELECT id FROM mining_profiles")){
            while(r.next())ids.add(UUID.fromString(r.getString(1)));return ids;
        }catch(SQLException e){throw io(e);}
    }
    void resetPickaxes()throws IOException{
        if("2".equals(meta("pickaxe-reset")))return;
        transaction(()->{for(UUID id:players()){
            var p=profile(id);if(p.pending()!=null)throw new IOException("Settle pending upgrades before resetting pickaxes");
            writeProfile(id,new Profile(UUID.randomUUID(),50,0,p.cargo(),p.recovery(),null));
        }writeMeta("pickaxe-reset","2");});
    }
    void replaceTerrain(Map<CaveLayout.Cell,String> cells,int initial,String version)throws IOException{
        transaction(()->{
            try(var q=connection.createStatement()){q.executeUpdate("DELETE FROM mining_cells");}
            writeCells(cells);writeMeta("initial-ores",Integer.toString(initial));writeMeta("layout-version",version);writeMeta("shell-built","");
        });
    }
    private void writeProfile(UUID id, Profile profile) throws SQLException, IOException {
        try (var q = connection.prepareStatement("INSERT INTO mining_profiles(id,data) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data")) {
            q.setString(1,id.toString()); q.setBytes(2,encode(profile)); q.executeUpdate();
        }
    }
    String meta(String key) throws IOException {
        try (var q = connection.prepareStatement("SELECT value FROM mining_meta WHERE key=?")) {
            q.setString(1,key); try (var r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        } catch (SQLException e) { throw io(e); }
    }
    void meta(String key, String value) throws IOException { transaction(() -> writeMeta(key,value)); }
    private void writeMeta(String key,String value) throws SQLException {
        try (var q = connection.prepareStatement("INSERT INTO mining_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            q.setString(1,key); q.setString(2,value); q.executeUpdate();
        }
    }
    Map<CaveLayout.Cell,String> cells() throws IOException {
        var result = new LinkedHashMap<CaveLayout.Cell,String>();
        try (var q = connection.createStatement(); var r = q.executeQuery("SELECT x,y,z,material FROM mining_cells ORDER BY x,z,y")) {
            while (r.next()) {
                var c = new CaveLayout.Cell(r.getInt(1),r.getInt(2),r.getInt(3)); String material=r.getString(4);
                if (!CaveLayout.mineable(c) || !(material.equals("STONE") || material.equals("AIR") || MiningRules.Ore.fromBlock(material)!=null))
                    throw new IOException("Invalid mining cell: " + c);
                result.put(c,material);
            }
            return result;
        } catch (SQLException e) { throw io(e); }
    }
    void replaceCells(Map<CaveLayout.Cell,String> cells, int initial) throws IOException {
        transaction(() -> {
            writeCells(cells);writeMeta("initial-ores",Integer.toString(initial));
        });
    }
    private void writeCells(Map<CaveLayout.Cell,String> cells)throws SQLException{
            try (var q = connection.prepareStatement("INSERT INTO mining_cells(x,y,z,material) VALUES(?,?,?,?) ON CONFLICT(x,y,z) DO UPDATE SET material=excluded.material")) {
                for (var entry : cells.entrySet()) {
                    var c=entry.getKey(); q.setInt(1,c.x()); q.setInt(2,c.y()); q.setInt(3,c.z()); q.setString(4,entry.getValue()); q.addBatch();
                }
                q.executeBatch();
            }
    }
    boolean mine(UUID player, CaveLayout.Cell cell, String expected, MiningRules.Ore ore, int amount) throws IOException {
        if (amount < 0 || (ore == null ? amount != 0 : amount < 1)) throw new IllegalArgumentException("Invalid ore credit");
        if (!CaveLayout.mineable(cell) || ore!=MiningRules.Ore.fromBlock(expected) || !(expected.equals("STONE") || ore!=null))throw new IllegalArgumentException("Invalid mine claim");
        boolean[] changed={false};
        transaction(() -> {
            try (var q=connection.prepareStatement("UPDATE mining_cells SET material='AIR' WHERE x=? AND y=? AND z=? AND material=? AND material!='AIR'")) {
                q.setInt(1,cell.x()); q.setInt(2,cell.y()); q.setInt(3,cell.z()); q.setString(4,expected);
                changed[0]=q.executeUpdate()==1;
            }
            if (changed[0]) {
                Profile p=profile(player);
                if (p.recovery==null || p.pending!=null) throw new IOException("Mining session is not ready");
                if(ore!=null)writeProfile(player,p.credit(ore,amount));
            }
        });
        return changed[0];
    }
    @FunctionalInterface private interface Write { void run() throws SQLException,IOException; }
    private void transaction(Write write) throws IOException {
        try {
            connection.setAutoCommit(false);
            try { write.run(); connection.commit(); }
            catch (SQLException | IOException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw io(e); }
    }
    private static IOException io(SQLException e) { return new IOException("Mining storage unavailable", e); }
    static byte[] encode(Profile p) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(2); uuid(out,p.tool); out.writeInt(p.speed); out.writeInt(p.fortune);
            for(var ore:MiningRules.Ore.values()) out.writeLong(p.cargo.getOrDefault(ore,0L));
            out.writeBoolean(p.recovery!=null);
            if(p.recovery!=null) { uuid(out,p.recovery.token); byte[] data=p.recovery.data; out.writeInt(data.length); out.write(data); }
            out.writeBoolean(p.pending!=null);
            if(p.pending!=null) { out.writeUTF(p.pending.id); out.writeUTF(p.pending.operation.name()); out.writeLong(p.pending.amount);out.writeUTF(p.pending.outcome.name());out.writeInt(p.pending.gain); }
        }
        return bytes.toByteArray();
    }
    static Profile decode(byte[] bytes) throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version=in.readInt();if(version!=1&&version!=2) throw new IOException("Unsupported mining profile version");
            UUID tool=uuid(in); int speed=in.readInt(), fortune=in.readInt(); var cargo=new EnumMap<MiningRules.Ore,Long>(MiningRules.Ore.class);
            for(var ore:MiningRules.Ore.values()) { long count=in.readLong(); if(count!=0)cargo.put(ore,count); }
            Recovery recovery=null;
            if(in.readBoolean()) {
                UUID token=uuid(in); int size=in.readInt(); if(size<1 || size>16_777_216)throw new IOException("Invalid recovery payload size");
                byte[] data=in.readNBytes(size); if(data.length!=size)throw new EOFException("Truncated recovery"); recovery=new Recovery(token,data);
            }
            Pending pending=null;
            if(in.readBoolean()){
                String id=in.readUTF();Operation op=Operation.valueOf(in.readUTF());long amount=in.readLong();
                pending=version==1?new Pending(id,op,amount,MiningRules.Outcome.SUCCESS,op==Operation.SPEED?25:1)
                        :new Pending(id,op,amount,MiningRules.Outcome.valueOf(in.readUTF()),in.readInt());
            }
            if(in.available()!=0)throw new IOException("Trailing profile data");
            return new Profile(tool,version==1?50+speed*25:speed,fortune,cargo,recovery,pending);
        } catch(IllegalArgumentException e) { throw new IOException("Invalid mining profile",e); }
    }
    private static void uuid(DataOutput out,UUID id)throws IOException { out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInput in)throws IOException { return new UUID(in.readLong(),in.readLong()); }
    @Override public void close() throws IOException { try { connection.close(); } catch(SQLException e) { throw io(e); } }
}
