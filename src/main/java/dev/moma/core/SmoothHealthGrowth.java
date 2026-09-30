package dev.moma.core;

import java.util.ArrayList;
import java.util.Properties;

public record SmoothHealthGrowth(double referenceRound, double referenceHealth, double baselineStrength,
                                 double baselineSharpness, double lossAmplitude, double lossScale,
                                 double lossPower, double lagCenter, double lagWidth, double lagPower) {
    public static final int SAMPLED_ROUNDS=10000;
    public SmoothHealthGrowth {
        for(double value:new double[]{referenceRound,referenceHealth,baselineSharpness,lossScale,lossPower,lagCenter,lagWidth})
            if(!Double.isFinite(value) || value<=0)throw new IllegalArgumentException("Invalid smooth growth coefficient");
        if(!Double.isFinite(baselineStrength) || baselineStrength<0 || !Double.isFinite(lossAmplitude)
                || lossAmplitude<0 || lossAmplitude>1)throw new IllegalArgumentException("Invalid damage compensation");
        if(!Double.isFinite(lagPower) || lagPower<2 || lagPower>6)throw new IllegalArgumentException("Invalid roster growth shape");
    }
    public static SmoothHealthGrowth from(Properties p) {
        return new SmoothHealthGrowth(number(p,"reference-round"),number(p,"reference-health"),
                number(p,"baseline-strength"),number(p,"baseline-sharpness"),number(p,"loss-amplitude"),
                number(p,"loss-scale"),number(p,"loss-power"),number(p,"lag-center"),number(p,"lag-width"),
                Double.parseDouble(p.getProperty("smooth-growth.lag-power","2")));
    }
    private static double number(Properties p,String key) {
        String value=p.getProperty("smooth-growth."+key);
        if(value==null)throw new IllegalArgumentException("Missing smooth-growth."+key);
        return Double.parseDouble(value);
    }
    public double damageRetention(double round) {
        return 1-lossAmplitude*Math.exp(-Math.pow(round/lossScale,lossPower));
    }
    private double referenceLog(double round,double power,double bend) {
        double x=round/referenceRound;
        return Math.log(referenceHealth)+power*Math.log(x)
                +bend*(Math.log1p(Math.pow(round/1000,2))-Math.log1p(Math.pow(referenceRound/1000,2)));
    }
    private double retainedLog(double round,double power,double bend) {
        double x=round/referenceRound;
        return referenceLog(round,power,bend)-baselineStrength*Math.log(x)/(1+Math.pow(x,baselineSharpness))
                +Math.log(damageRetention(round));
    }
    public double health(double round,HealthCurve.Anchor start,double power,double bend) {
        if(round<start.round())throw new IllegalArgumentException("Round precedes smooth growth");
        double join=retainedLog(start.round(),power,bend)-Math.log(start.health());
        double exponent=Math.pow(Math.abs(Math.log(start.round()/lagCenter)/lagWidth),lagPower)
                -Math.pow(Math.abs(Math.log(round/lagCenter)/lagWidth),lagPower);
        return Math.exp(retainedLog(round,power,bend)-join*Math.exp(exponent));
    }
    public HealthCurve compile(HealthCurve prefix,double power,double bend) {
        HealthCurve.Anchor start=prefix.anchors().getLast();
        if(start.round()>=SAMPLED_ROUNDS)throw new IllegalArgumentException("Smooth growth needs a shorter early curve");
        double end=health(SAMPLED_ROUNDS,start,power,bend);
        if(Math.abs(Math.log(end)-referenceLog(SAMPLED_ROUNDS,power,bend))>1e-12)
            throw new IllegalArgumentException("Smooth growth has not converged to its asymptote");
        var anchors=new ArrayList<>(prefix.anchors());
        for(int round=start.round()+1;round<=SAMPLED_ROUNDS;round++)
            anchors.add(new HealthCurve.Anchor(round,health(round,start,power,bend)));
        return new HealthCurve(anchors);
    }
}
