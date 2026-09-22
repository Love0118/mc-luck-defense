package dev.moma.benchmark;

import dev.moma.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Run write with the published 0.18.2 JAR, then read with the new JAR/classes. */
public final class IncomeSnapshotProbe {
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[1]);
        if(args[0].equals("write")) {
            Arena arena=new Arena("a",new UUID(0,1),new Grid(6),1000,100,
                    new TraitLoadout(List.of("gold_spent_10000000")),new HashRandom(42));
            for(int i=0;i<6;i++)arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
            try(var output=new ObjectOutputStream(Files.newOutputStream(file))){output.writeObject(arena);}
        } else if(args[0].equals("read")) {
            Arena arena;try(var input=new ObjectInputStream(Files.newInputStream(file))){arena=(Arena)input.readObject();}
            if(!arena.mergingEnabled() || arena.spentGold()!=60 || arena.coins()!=940 || arena.defenderCount()!=1
                    || arena.defenders().getFirst().enhancement()!=5 || arena.damageMultiplier(AttackRole.MELEE_SINGLE,false)!=1
                    || arena.traits().value(TraitCatalog.Family.GOLD_INCOME)!=8)throw new AssertionError("Snapshot migration");
            for(int i=0;i<100;i++) {
                Enemy enemy=new Enemy(UUID.randomUUID(),arena.id(),EnemyType.ZOMBIE,1,1,.1,false);
                arena.addEnemy(enemy);enemy.damage(1);arena.collectDeadEnemies();
            }
            if(arena.coins()!=950.8 || arena.earnedCoins()!=10.8)throw new AssertionError("Income after migration");
            System.out.println("0.18.2 snapshot restored: gold, units, enhancement, spending retained; merge ON; income +8%");
        } else throw new IllegalArgumentException(args[0]);
    }
}
