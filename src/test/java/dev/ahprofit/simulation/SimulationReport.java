package dev.ahprofit.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Accumulates the measurements a simulation run produces, and renders them as text. */
final class SimulationReport {
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final List<String> findings = new ArrayList<>();
    private double worstCeilingRatio = Double.POSITIVE_INFINITY;
    private double worstNetProfit = Double.POSITIVE_INFINITY;
    private int ceilingSamples;
    private int ceilingAboveTruth;
    private final Map<Integer, int[]> calibration = new LinkedHashMap<>();

    void count(String name) {
        count(name, 1);
    }

    void count(String name, long amount) {
        counters.merge(name, amount, Long::sum);
    }

    void finding(String text) {
        findings.add(text);
    }

    void recordCeiling(double ceilingUnit, double trueUnit) {
        if (Double.isNaN(ceilingUnit) || Double.isInfinite(ceilingUnit) || trueUnit <= 0) {
            return;
        }
        ceilingSamples++;
        if (trueUnit > 0) {
            double ratio = ceilingUnit / trueUnit;
            worstCeilingRatio = Math.min(worstCeilingRatio, ratio);
            if (ratio > 1.0) {
                ceilingAboveTruth++;
            }
        }
    }

    void recordNetProfit(double netProfit) {
        worstNetProfit = Math.min(worstNetProfit, netProfit);
    }

    /** Buckets confidence into tenths so calibration can be read off directly. */
    void recordCalibration(double confidence, boolean ceilingWithinTruth) {
        int bucket = (int) Math.round(Math.min(0.99, Math.max(0.0, confidence)) * 10.0);
        calibration.computeIfAbsent(bucket, ignored -> new int[2]);
        calibration.get(bucket)[0]++;
        if (ceilingWithinTruth) {
            calibration.get(bucket)[1]++;
        }
    }

    long counter(String name) {
        return counters.getOrDefault(name, 0L);
    }

    int ceilingSamples() {
        return ceilingSamples;
    }

    int ceilingAboveTruth() {
        return ceilingAboveTruth;
    }

    double worstCeilingRatio() {
        return ceilingSamples == 0 ? Double.NaN : worstCeilingRatio;
    }

    double worstNetProfit() {
        return ceilingSamples == 0 ? Double.NaN : worstNetProfit;
    }

    String render() {
        StringBuilder text = new StringBuilder();
        text.append("=== AhProfit simulation report ===\n");
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            text.append(String.format("%-46s %d%n", entry.getKey(), entry.getValue()));
        }
        if (ceilingSamples > 0) {
            text.append(String.format("%-46s %d / %d (%.1f%%)%n", "resale ceiling above true clearing price",
                    ceilingAboveTruth, ceilingSamples, 100.0 * ceilingAboveTruth / ceilingSamples));
            text.append(String.format("%-46s %.4f%n", "lowest ceiling / true price ratio", worstCeilingRatio));
            text.append(String.format("%-46s %.2f%n", "worst net profit at the true clearing price", worstNetProfit));
        }
        if (!calibration.isEmpty()) {
            text.append("confidence calibration (bucket -> within truth / samples):\n");
            for (Map.Entry<Integer, int[]> entry : calibration.entrySet()) {
                int[] bucket = entry.getValue();
                double share = bucket[0] == 0 ? 0 : 100.0 * bucket[1] / bucket[0];
                text.append(String.format("  0.%d  %5d / %5d  (%.0f%% within truth)%n",
                        entry.getKey(), bucket[1], bucket[0], share));
            }
        }
        if (!findings.isEmpty()) {
            text.append("findings:\n");
            for (String finding : findings) {
                text.append("  - ").append(finding).append('\n');
            }
        }
        return text.toString();
    }
}
