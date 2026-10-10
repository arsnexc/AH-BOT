package dev.ahprofit.scanner;

import dev.ahprofit.market.Opportunity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Suppresses repeats and emits a new alert only when a listing's economics/risk changed materially. */
public final class AlertDeduplicator {
    private static final long RETENTION_MILLIS = 24L * 60L * 60L * 1_000L;
    private final Map<String, Stamp> lastAlerts = new HashMap<>();

    public synchronized List<Opportunity> collect(List<Opportunity> current, long nowMillis) {
        expire(nowMillis);
        List<Opportunity> changed = new ArrayList<>();
        for (Opportunity opportunity : current) {
            String key = opportunity.listing().listingKey();
            Stamp previous = lastAlerts.get(key);
            if (previous == null || changedMaterially(previous, opportunity)) {
                changed.add(opportunity);
                lastAlerts.put(key, Stamp.from(opportunity, nowMillis));
            } else {
                lastAlerts.put(key, previous.withLastSeen(nowMillis));
            }
        }
        return List.copyOf(changed);
    }

    public synchronized int trackedListings() {
        return lastAlerts.size();
    }

    private void expire(long nowMillis) {
        Iterator<Map.Entry<String, Stamp>> iterator = lastAlerts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().getValue().lastSeenMillis() > RETENTION_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static boolean changedMaterially(Stamp previous, Opportunity next) {
        BigDecimal askChange = relativeChange(previous.ask(), next.listing().totalPrice());
        BigDecimal profitChange = relativeChange(previous.netProfit(), next.estimatedNetProfit());
        return askChange.compareTo(new BigDecimal("0.02")) >= 0
                || profitChange.compareTo(new BigDecimal("0.10")) >= 0
                || Math.abs(previous.confidence() - next.estimate().confidence()) >= 0.15
                || previous.liquidity() != next.estimate().liquidity();
    }

    private static BigDecimal relativeChange(BigDecimal previous, BigDecimal next) {
        BigDecimal scale = previous.abs().max(BigDecimal.ONE);
        return next.subtract(previous).abs().divide(scale, java.math.MathContext.DECIMAL128);
    }

    private record Stamp(BigDecimal ask, BigDecimal netProfit, double confidence,
                         dev.ahprofit.market.LiquidityLevel liquidity, long lastSeenMillis) {
        static Stamp from(Opportunity opportunity, long nowMillis) {
            return new Stamp(opportunity.listing().totalPrice(), opportunity.estimatedNetProfit(),
                    opportunity.estimate().confidence(), opportunity.estimate().liquidity(), nowMillis);
        }

        Stamp withLastSeen(long time) {
            return new Stamp(ask, netProfit, confidence, liquidity, time);
        }
    }
}
