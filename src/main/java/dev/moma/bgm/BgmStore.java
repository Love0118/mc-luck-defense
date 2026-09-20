package dev.moma.bgm;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** All access is serialized by the BGM worker. */
public final class BgmStore implements AutoCloseable {
    private final Connection connection;
    private final int uploadsPerPlayer;
    public BgmStore(Path path) throws Exception {
        this(path, BgmLimits.DEFAULT.uploadsPerPlayer());
    }
    public BgmStore(Path path, int uploadsPerPlayer) throws Exception {
        if(uploadsPerPlayer<1)throw new IllegalArgumentException("Invalid upload limit");
        this.uploadsPerPlayer=uploadsPerPlayer;
        Class.forName("org.sqlite.JDBC");
        connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(var statement=connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,uploader TEXT NOT NULL,uploader_name TEXT NOT NULL,title TEXT NOT NULL,youtube_url TEXT NOT NULL,delivery_url TEXT NOT NULL,sha1 TEXT NOT NULL,seconds REAL NOT NULL)");
            boolean version=false;
            try(var columns=statement.executeQuery("PRAGMA table_info(tracks)")){while(columns.next())version|=columns.getString("name").equals("pack_version");}
            if(!version)statement.execute("ALTER TABLE tracks ADD COLUMN pack_version INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE TABLE IF NOT EXISTS playlists(owner TEXT PRIMARY KEY,track_ids TEXT NOT NULL,mode TEXT NOT NULL,selected TEXT NOT NULL)");
        }
    }
    public List<Track> list() throws SQLException {
        var result=new ArrayList<Track>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM tracks ORDER BY rowid")) {
            while(rows.next()) result.add(new Track(rows.getString("id"),UUID.fromString(rows.getString("uploader")),rows.getString("uploader_name"),
                    rows.getString("title"),rows.getString("youtube_url"),rows.getString("delivery_url"),rows.getString("sha1"),rows.getDouble("seconds"),rows.getInt("pack_version")));
        }
        return List.copyOf(result);
    }
    public void save(Track track) throws SQLException {
        if(!track.id().equals("default")) {
            // Existing tracks remain repairable even after lowering the upload limit.
            try(var limit=connection.prepareStatement("SELECT COUNT(*) FROM tracks WHERE uploader=? AND NOT EXISTS (SELECT 1 FROM tracks WHERE id=?)")) {
                limit.setString(1,track.uploader().toString());limit.setString(2,track.id());
                try(var rows=limit.executeQuery()){if(rows.next() && rows.getInt(1)>=uploadsPerPlayer)throw new SQLException("Uploader reached track limit: "+uploadsPerPlayer);}
            }
        }
        try(var statement=connection.prepareStatement("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,delivery_url=excluded.delivery_url,sha1=excluded.sha1,seconds=excluded.seconds,pack_version=excluded.pack_version")) {
            statement.setString(1,track.id()); statement.setString(2,track.uploader().toString());statement.setString(3,track.uploaderName());
            statement.setString(4,track.title());statement.setString(5,track.youtubeUrl());statement.setString(6,track.deliveryUrl());
            statement.setString(7,track.sha1());statement.setDouble(8,track.seconds());statement.setInt(9,track.packVersion());statement.executeUpdate();
        }
    }
    public Map<UUID,BgmPlaylist> playlists()throws SQLException {
        Map<UUID,BgmPlaylist> result=new HashMap<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM playlists")) {
            while(rows.next()) {
                String ids=rows.getString("track_ids");
                result.put(UUID.fromString(rows.getString("owner")),new BgmPlaylist(ids.isEmpty()?List.of():List.of(ids.split(",")),BgmTimeline.Mode.valueOf(rows.getString("mode")),rows.getString("selected")));
            }
        }
        return Map.copyOf(result);
    }
    public void savePlaylist(UUID owner,BgmPlaylist playlist)throws SQLException {
        try(var statement=connection.prepareStatement("INSERT INTO playlists VALUES(?,?,?,?) ON CONFLICT(owner) DO UPDATE SET track_ids=excluded.track_ids,mode=excluded.mode,selected=excluded.selected")) {
            statement.setString(1,owner.toString());statement.setString(2,String.join(",",playlist.tracks()));
            statement.setString(3,playlist.mode().name());statement.setString(4,playlist.selected());statement.executeUpdate();
        }
    }
    @Override public void close() throws SQLException { connection.close(); }
}
