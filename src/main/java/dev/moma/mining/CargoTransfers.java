package dev.moma.mining;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/** Ore debit/credit and the transferable ground claim commit in the same mining database transaction. */
final class CargoTransfers implements AutoCloseable {
    record Claim(MiningRules.Ore ore,int amount) {}
    private final MiningStore store;
    private final Connection connection;
    CargoTransfers(Path path,MiningStore store)throws IOException{
        this.store=store;
        try{
            connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            try(var sql=connection.createStatement()){
                sql.execute("PRAGMA busy_timeout=3000");sql.execute("PRAGMA synchronous=FULL");
                sql.execute("CREATE TABLE IF NOT EXISTS mining_cargo_drops(id TEXT PRIMARY KEY,ore TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 64))");
            }
        }catch(SQLException error){throw new IOException("Ore transfer storage unavailable",error);}
    }
    Claim claim(UUID id)throws IOException{
        try(var query=connection.prepareStatement("SELECT ore,amount FROM mining_cargo_drops WHERE id=?")){
            query.setString(1,id.toString());try(var rows=query.executeQuery()){return rows.next()?new Claim(MiningRules.Ore.valueOf(rows.getString(1)),rows.getInt(2)):null;}
        }catch(SQLException error){throw new IOException(error);}
    }
    void drop(UUID player,UUID id,MiningRules.Ore ore,int amount)throws IOException{
        if(amount<1||amount>64)throw new IllegalArgumentException("Invalid ore drop");
        transaction(()->{
            Claim previous=claim(id);if(previous!=null){if(previous.ore()!=ore||previous.amount()!=amount)throw new IOException("Conflicting ore claim");return;}
            var profile=store.profile(player);
            if(profile.pending()!=null||profile.cargo().getOrDefault(ore,0L)<amount)throw new IOException("Ore balance is not ready");
            write(player,profile.credit(ore,-amount));
            try(var query=connection.prepareStatement("INSERT INTO mining_cargo_drops(id,ore,amount) VALUES(?,?,?)")){
                query.setString(1,id.toString());query.setString(2,ore.name());query.setInt(3,amount);query.executeUpdate();
            }
        });
    }
    int pickup(UUID id,UUID player,int requested)throws IOException{
        if(requested<1)return 0;int[] received={0};
        transaction(()->{
            Claim claim=claim(id);if(claim==null)return;
            var profile=store.profile(player);if(profile.pending()!=null)throw new IOException("Ore sale is pending");
            int amount=Math.min(requested,claim.amount());write(player,profile.credit(claim.ore(),amount));
            if(amount==claim.amount())delete(id);
            else try(var query=connection.prepareStatement("UPDATE mining_cargo_drops SET amount=? WHERE id=?")){
                query.setInt(1,claim.amount()-amount);query.setString(2,id.toString());query.executeUpdate();
            }
            received[0]=amount;
        });return received[0];
    }
    void retire(UUID id)throws IOException{transaction(()->delete(id));}
    private void delete(UUID id)throws SQLException{
        try(var query=connection.prepareStatement("DELETE FROM mining_cargo_drops WHERE id=?")){query.setString(1,id.toString());query.executeUpdate();}
    }
    private void write(UUID player,MiningStore.Profile profile)throws SQLException,IOException{
        try(var query=connection.prepareStatement("INSERT INTO mining_profiles(id,data) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data")){
            query.setString(1,player.toString());query.setBytes(2,MiningStore.encode(profile));query.executeUpdate();
        }
    }
    @FunctionalInterface private interface Write{void run()throws SQLException,IOException;}
    private void transaction(Write work)throws IOException{
        try{
            connection.setAutoCommit(false);
            try{work.run();connection.commit();}
            catch(SQLException|IOException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
            finally{connection.setAutoCommit(true);}
        }catch(SQLException failure){throw new IOException("Ore transfer did not commit",failure);}
    }
    @Override public void close()throws IOException{try{connection.close();}catch(SQLException error){throw new IOException(error);}}
}
