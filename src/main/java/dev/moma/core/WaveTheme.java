package dev.moma.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit, shared route for the live server, simulator and wave export. */
public record WaveTheme(int round, String biome, Stage stage, String name, List<EnemyType> roster, EnemyType boss, int wardens) {
    public enum Stage {
        OVERWORLD("오버월드"), ANCIENT_CITY("고대 도시"), NETHER("네더"), END("엔드"), END_CITY("엔드 시티");
        private final String label;
        Stage(String label) { this.label=label; }
        public String label() { return label; }
    }
    private static final List<WaveTheme> ALL=load();
    public WaveTheme { roster=List.copyOf(roster); }
    public static List<WaveTheme> all() { return ALL; }
    public static WaveTheme at(int round) {
        if(round<1)throw new IllegalArgumentException("Round must be positive");
        return ALL.get((round-1)%100);
    }
    public String displayName() { return stage.label()+" · "+name; }
    private static List<WaveTheme> load() {
        try(var stream=WaveTheme.class.getResourceAsStream("/wave-themes.tsv")) {
            if(stream==null)throw new IllegalStateException("Missing wave-themes.tsv");
            var lines=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().skip(1).toList();
            var themes=new ArrayList<WaveTheme>();
            for(String line:lines) {
                if(line.isBlank())continue;
                String[] fields=line.split("\t");
                if(fields.length!=7)throw new IllegalArgumentException("Invalid theme row: "+line);
                int round=Integer.parseInt(fields[0]);
                var theme=new WaveTheme(round,fields[1],Stage.valueOf(fields[2]),fields[3],
                        Arrays.stream(fields[4].split(",")).map(EnemyType::valueOf).toList(),
                        fields[5].equals("-")?null:EnemyType.valueOf(fields[5]),Integer.parseInt(fields[6]));
                if(round!=themes.size()+1 || theme.roster.isEmpty() || !theme.biome.matches("[a-z_]+")
                        || (theme.boss!=null)!=(round%10==0) || theme.wardens<0 || theme.wardens>2)
                    throw new IllegalArgumentException("Invalid theme: "+line);
                themes.add(theme);
            }
            if(themes.size()!=100)throw new IllegalArgumentException("Expected 100 themes");
            return List.copyOf(themes);
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
}
