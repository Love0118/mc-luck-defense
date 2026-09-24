package dev.moma.core;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class LeaderboardStore {
    public enum Season {
        PRESEASON(0), SEASON_ONE(1);
        private final int id;
        Season(int id){this.id=id;}
    }
    private final Path database;

    public LeaderboardStore(Path directory)throws IOException {
        Files.createDirectories(directory);
        database=directory.resolve("leaderboards.db");
        try {
            Class.forName("org.sqlite.JDBC");
            try(var connection=open();var statement=connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                try {
                    int version;
                    try(var rows=statement.executeQuery("PRAGMA user_version")){rows.next();version=rows.getInt(1);}
                    if(version<0 || version>1)throw new IOException("Unsupported leaderboard schema: "+version);
                    if(version==0) {
                        statement.execute("CREATE TABLE round_records(season INTEGER NOT NULL CHECK(season IN (0,1)),player_uuid TEXT NOT NULL,player_name TEXT NOT NULL,round INTEGER NOT NULL CHECK(round>=1),PRIMARY KEY(season,player_uuid))");
                        var legacy=RoundRecords.load(directory.resolve("round-records.properties"));
                        write(connection,Season.PRESEASON,legacy.snapshot());
                        statement.execute("PRAGMA user_version=1");
                    }
                    statement.execute("COMMIT");
                } catch(IOException|SQLException|RuntimeException error) {
                    try{statement.execute("ROLLBACK");}catch(SQLException rollback){error.addSuppressed(rollback);}
                    throw error;
                }
            }
        } catch(ClassNotFoundException|SQLException error){throw new IOException("Leaderboard migration failed",error);}
    }

    private Connection open()throws SQLException {
        Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());
        try(var statement=connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            return connection;
        } catch(SQLException error){connection.close();throw error;}
    }

    public RoundRecords load(Season season)throws IOException {
        var result=new RoundRecords();
        try(var connection=open();var statement=connection.prepareStatement("SELECT player_uuid,player_name,round FROM round_records WHERE season=?")) {
            statement.setInt(1,season.id);
            try(var rows=statement.executeQuery()) {
                while(rows.next())result.record(UUID.fromString(rows.getString(1)),rows.getString(2),rows.getInt(3));
            }
        } catch(SQLException|IllegalArgumentException error){throw new IOException("Leaderboard read failed",error);}
        return result;
    }

    public void saveCurrent(List<RoundRecords.Entry> entries)throws IOException {
        try(var connection=open()) {
            connection.setAutoCommit(false);
            try {
                write(connection,Season.SEASON_ONE,entries);
                connection.commit();
            } catch(SQLException|RuntimeException error) {
                try{connection.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}
                throw error;
            }
        } catch(SQLException error){throw new IOException("Leaderboard save failed",error);}
    }

    private static void write(Connection connection,Season season,List<RoundRecords.Entry> entries)throws SQLException {
        try(var statement=connection.prepareStatement("INSERT INTO round_records(season,player_uuid,player_name,round) VALUES(?,?,?,?) ON CONFLICT(season,player_uuid) DO UPDATE SET player_name=excluded.player_name,round=MAX(round_records.round,excluded.round)")) {
            for(var entry:entries) {
                statement.setInt(1,season.id);statement.setString(2,entry.player().toString());
                statement.setString(3,entry.name());statement.setInt(4,entry.round());statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
