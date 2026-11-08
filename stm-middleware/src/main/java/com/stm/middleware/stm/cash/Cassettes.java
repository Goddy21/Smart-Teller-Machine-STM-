package com.stm.middleware.stm.cash;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.Errors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * What the Gateway believes each kiosk holds, per currency and denomination. Seeded with a starting float on first use
 * (posted to the kiosk's cash GL so it reconciles), decreased when cash is taken, increased when deposits and foreign
 * notes are committed, and overwritten by the device's own counts (A-2).
 */
@Service
public class Cassettes {
    public static final List<String> FOREIGN = List.of("USD", "EUR", "GBP");
    public static final BigDecimal FOREIGN_DAILY_MAX = new BigDecimal("1000.00");

    /** Starting float: currency -> denomination -> notes. GBP starts empty to show "out of stock". */
    private static final Map<String, Map<Integer, Integer>> FLOAT = new LinkedHashMap<>();
    static {
        FLOAT.put("KES", new LinkedHashMap<>(Map.of(1000, 800, 500, 600, 200, 300, 100, 500)));
        FLOAT.put("USD", new LinkedHashMap<>(Map.of(100, 64, 50, 40)));
        FLOAT.put("EUR", new LinkedHashMap<>(Map.of(100, 40, 50, 30)));
        FLOAT.put("GBP", new LinkedHashMap<>(Map.of(50, 0, 20, 0)));
    }

    public record Note(String denomination, int count) {}

    private final JdbcClient jdbc;
    private final CoreBanking core;

    public Cassettes(JdbcClient jdbc, CoreBanking core) {
        this.jdbc = jdbc;
        this.core = core;
    }

    public static String cashGl(String currency, String deviceId) {
        return "GL-CASH-" + currency + "-" + deviceId;
    }

    public static String retractGl(String currency, String deviceId) {
        return "GL-RETRACT-" + currency + "-" + deviceId;
    }

    @Transactional
    public void ensureSeeded(long terminalId, String deviceId) {
        long n = jdbc.sql("SELECT count(*) FROM cassette_inventory WHERE terminal_id = :t").param("t", terminalId)
                .query(Long.class).single();
        if (n > 0) return;
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", 880_000L + terminalId).query((rs, i) -> 1).list();
        n = jdbc.sql("SELECT count(*) FROM cassette_inventory WHERE terminal_id = :t").param("t", terminalId)
                .query(Long.class).single();
        if (n > 0) return;
        FLOAT.forEach((cur, notes) -> {
            BigDecimal value = BigDecimal.ZERO;
            for (var e : notes.entrySet()) {
                jdbc.sql("INSERT INTO cassette_inventory (terminal_id, currency, denomination, note_count) VALUES (:t, :c, :d, :n)")
                        .param("t", terminalId).param("c", cur).param("d", e.getKey()).param("n", e.getValue()).update();
                value = value.add(BigDecimal.valueOf((long) e.getKey() * e.getValue()));
            }
            if (value.signum() > 0) {
                core.transferGl(cashGl(cur, deviceId), "GL-VAULT-" + cur, value, cur, "STM float loaded " + deviceId);
            }
        });
    }

    /** denomination -> notes, largest first. */
    public SortedMap<Integer, Integer> notes(long terminalId, String currency) {
        SortedMap<Integer, Integer> m = new TreeMap<>(Comparator.reverseOrder());
        jdbc.sql("SELECT denomination, note_count FROM cassette_inventory WHERE terminal_id = :t AND currency = :c")
                .param("t", terminalId).param("c", currency)
                .query(rs -> { m.put(rs.getBigDecimal(1).intValue(), rs.getInt(2)); });
        return m;
    }

    /**
     * Notes for an amount: the device's proposal if it adds up and is in stock, else largest notes first, breaking one
     * large note into smaller ones when that keeps change available (5,000 -> 4 x 1,000 + 2 x 500).
     */
    public List<Note> mix(long terminalId, String currency, BigDecimal amount, List<Note> proposed) {
        SortedMap<Integer, Integer> stock = notes(terminalId, currency);
        if (amount.stripTrailingZeros().scale() > 0) throw Errors.cassetteUnavailable(currency);
        long target = amount.longValueExact();
        if (proposed != null && !proposed.isEmpty()) {
            long sum = 0;
            boolean ok = true;
            for (Note p : proposed) {
                int d = Integer.parseInt(p.denomination());
                sum += (long) d * p.count();
                if (stock.getOrDefault(d, 0) < p.count()) ok = false;
            }
            if (ok && sum == target) return proposed;
        }
        Map<Integer, Integer> used = new LinkedHashMap<>();
        long left = target;
        for (var e : stock.entrySet()) {
            int take = (int) Math.min(e.getValue(), left / e.getKey());
            if (take > 0) {
                used.put(e.getKey(), take);
                left -= (long) take * e.getKey();
            }
        }
        if (left != 0) throw Errors.cassetteUnavailable(currency);
        // Break one top note into two halves if it is the only denomination used and the halves are in stock.
        if (used.size() == 1) {
            int top = used.keySet().iterator().next();
            int half = top / 2;
            if (used.get(top) > 2 && top % 2 == 0 && stock.getOrDefault(half, 0) >= 2) {
                used.put(top, used.get(top) - 1);
                used.put(half, 2);
            }
        }
        List<Note> out = new ArrayList<>();
        used.forEach((d, c) -> out.add(new Note(String.valueOf(d), c)));
        return out;
    }

    public static BigDecimal value(List<Note> notes) {
        BigDecimal v = BigDecimal.ZERO;
        for (Note n : notes) v = v.add(new BigDecimal(n.denomination()).multiply(BigDecimal.valueOf(n.count())));
        return v;
    }

    public void take(long terminalId, String currency, List<Note> notes) {
        for (Note n : notes) {
            jdbc.sql("""
                    UPDATE cassette_inventory SET note_count = greatest(note_count - :n, 0), updated_at = now()
                    WHERE terminal_id = :t AND currency = :c AND denomination = :d""")
                    .param("n", n.count()).param("t", terminalId).param("c", currency)
                    .param("d", new BigDecimal(n.denomination())).update();
        }
    }

    public void add(long terminalId, String currency, List<Note> notes) {
        for (Note n : notes) {
            jdbc.sql("""
                    INSERT INTO cassette_inventory (terminal_id, currency, denomination, note_count) VALUES (:t, :c, :d, :n)
                    ON CONFLICT (terminal_id, currency, denomination)
                    DO UPDATE SET note_count = cassette_inventory.note_count + :n, updated_at = now()""")
                    .param("n", n.count()).param("t", terminalId).param("c", currency)
                    .param("d", new BigDecimal(n.denomination())).update();
        }
    }

    public void set(long terminalId, String currency, int denomination, int count) {
        jdbc.sql("""
                INSERT INTO cassette_inventory (terminal_id, currency, denomination, note_count) VALUES (:t, :c, :d, :n)
                ON CONFLICT (terminal_id, currency, denomination) DO UPDATE SET note_count = :n, updated_at = now()""")
                .param("n", count).param("t", terminalId).param("c", currency).param("d", denomination).update();
    }

    /** X-6: per currency, the denominations in stock and whether the kiosk can sell it. */
    public List<Map<String, Object>> view(long terminalId) {
        Set<String> currencies = new LinkedHashSet<>();
        jdbc.sql("SELECT DISTINCT currency FROM cassette_inventory WHERE terminal_id = :t ORDER BY currency")
                .param("t", terminalId).query(rs -> { currencies.add(rs.getString(1)); });
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> order = new ArrayList<>(FOREIGN);
        order.add("KES");
        for (String cur : order) {
            if (!currencies.contains(cur)) continue;
            List<String> denoms = new ArrayList<>();
            notes(terminalId, cur).entrySet().stream().filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.comparingByKey()).forEach(e -> denoms.add(String.valueOf(e.getKey())));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", cur);
            m.put("denominations", denoms);
            m.put("status", denoms.isEmpty() ? "OUT_OF_STOCK" : "AVAILABLE");
            if (FOREIGN.contains(cur)) {
                if (denoms.isEmpty()) m.put("nextRefill", LocalDate.now().plusDays(1).toString());
                else m.put("maxPerCustomerDaily", FOREIGN_DAILY_MAX.toPlainString());
            }
            out.add(m);
        }
        return out;
    }
}
