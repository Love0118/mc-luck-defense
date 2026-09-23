package dev.moma.benchmark;

import dev.moma.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ReserveSnapshotProbe {
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[1]);
        if(args[0].equals("write")) {
            Arena a=new Arena("a",new UUID(0,1),new Grid(6),1000,100);
            a.summon(a.owner(),new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL),(t,r,c)->UUID.randomUUID());
            try(var out=new ObjectOutputStream(Files.newOutputStream(file))){out.writeObject(a);}
        } else {
            Arena a;try(var in=new ObjectInputStream(Files.newInputStream(file))){a=(Arena)in.readObject();}
            if(a.coins()!=990 || a.reserveCount()!=0 || a.defenderCount()!=1 || a.defenders().getFirst().saleValue()!=1000)throw new AssertionError();
            a.select(a.owner(),a.defenders().getFirst().entityId());a.benchSelected(a.owner());
            if(a.reserveCount()!=1 || a.defenderCount()!=0)throw new AssertionError();
            a.reachedRound(2500);if(a.summonCost()!=10000)throw new AssertionError();
            System.out.println("Published 0.18.4 snapshot restored with reserve, sale value and new tier transitions");
        }
    }
}
