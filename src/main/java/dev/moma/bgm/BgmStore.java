package dev.moma.bgm;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** All access is serialized by the BGM worker. */
public final class BgmStore implements AutoCloseable {
    private final Connection connection;
    public BgmStore(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(var statement=connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,uploader TEXT NOT NULL,uploader_name TEXT NOT NULL,title TEXT NOT NULL,youtube_url TEXT NOT NULL,delivery_url TEXT NOT NULL,sha1 TEXT NOT NULL,seconds REAL NOT NULL)");
        }
    }
    public List<Track> list() throws SQLException {
        var result=new ArrayList<Track>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM tracks ORDER BY rowid")) {
            while(rows.next()) result.add(new Track(rows.getString("id"),UUID.fromString(rows.getString("uploader")),rows.getString("uploader_name"),
                    rows.getString("title"),rows.getString("youtube_url"),rows.getString("delivery_url"),rows.getString("sha1"),rows.getDouble("seconds")));
        }
        return List.copyOf(result);
    }
    public void save(Track track) throws SQLException {
        if(!track.id().equals("default")) {
            try(var limit=connection.prepareStatement("SELECT COUNT(*) FROM tracks WHERE uploader=? AND id<>?")) {
                limit.setString(1,track.uploader().toString());limit.setString(2,track.id());
                try(var rows=limit.executeQuery()){if(rows.next() && rows.getInt(1)>=3)throw new SQLException("Uploader already has three tracks");}
            }
        }
        try(var statement=connection.prepareStatement("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,delivery_url=excluded.delivery_url,sha1=excluded.sha1,seconds=excluded.seconds")) {
            statement.setString(1,track.id()); statement.setString(2,track.uploader().toString());statement.setString(3,track.uploaderName());
            statement.setString(4,track.title());statement.setString(5,track.youtubeUrl());statement.setString(6,track.deliveryUrl());
            statement.setString(7,track.sha1());statement.setDouble(8,track.seconds());statement.executeUpdate();
        }
    }
    @Override public void close() throws SQLException { connection.close(); }
}
