import java.util.*;

/** Independent exhaustive oracle for small databases, not used in benchmarks. */
public class LCMTest {
    public static void main(String[] args) throws Exception {
        Random random = new Random(2026);
        int checks = 0;
        for (int test = 0; test < 100; test++) {
            int m = 1 + random.nextInt(8), n = 1 + random.nextInt(10);
            int[] labels = new int[m];
            int[][] rows = new int[n][];
            for (int i = 0; i < m; i++) labels[i] = i;
            for (int t = 0; t < n; t++) {
                List<Integer> items = new ArrayList<>();
                for (int i = 0; i < m; i++) if (random.nextBoolean()) items.add(i);
                rows[t] = items.stream().mapToInt(Integer::intValue).toArray();
            }
            int[] supports = new int[1 << m];
            for (int mask = 0; mask < supports.length; mask++) {
                for (int[] row : rows) {
                    int transaction = 0;
                    for (int item : row) transaction |= 1 << item;
                    if ((transaction & mask) == mask) supports[mask]++;
                }
            }
            for (int threshold = 1; threshold <= n + 1; threshold++) {
                Map<Integer, Integer> expected = new HashMap<>(), actual = new HashMap<>();
                for (int mask = 1; mask < supports.length; mask++) {
                    if (supports[mask] < threshold) continue;
                    boolean closed = true;
                    for (int item = 0; item < m; item++) {
                        if ((mask & (1 << item)) == 0 && supports[mask | (1 << item)] == supports[mask])
                            closed = false;
                    }
                    if (closed) expected.put(mask, supports[mask]);
                }
                LCM.Miner miner = new LCM.Miner(new LCM.Database(rows, labels), threshold,
                    (pattern, support) -> {
                        int mask = 0;
                        for (int i = pattern.nextSetBit(0); i >= 0; i = pattern.nextSetBit(i + 1)) mask |= 1 << i;
                        if (actual.put(mask, support) != null) throw new AssertionError("Duplicate output");
                    }, 30);
                miner.mine();
                if (!expected.equals(actual) || miner.closedCount != expected.size())
                    throw new AssertionError("Incorrect result: case " + test + " threshold " + threshold);
                checks++;
            }
        }
        System.out.println("PASS: " + checks + " exhaustive comparisons over 100 random databases.");
    }
}
