import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Educational LCM: occurrence delivery + prefix-preserving closure extension.
 * No external libraries. Items are integer IDs; one transaction per line.
 */
public class LCM {
    static class Database {
        final int[][] transactions;
        final int[] labels;
        Database(int[][] transactions, int[] labels) {
            this.transactions = transactions; this.labels = labels;
        }
        static Database read(Path path) throws IOException {
            List<int[]> rows = new ArrayList<>();
            TreeSet<Integer> universe = new TreeSet<>();
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("%") || line.startsWith("@")) continue;
                    String[] tokens = line.split("\\s+");
                    int[] row = new int[tokens.length];
                    try {
                        for (int i = 0; i < tokens.length; i++) row[i] = Integer.parseInt(tokens[i]);
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid integer in " + path + " at line " + lineNumber, e);
                    }
                    row = Arrays.stream(row).distinct().sorted().toArray();
                    for (int item : row) universe.add(item);
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) throw new IOException("No transactions: " + path);
            int[] labels = universe.stream().mapToInt(Integer::intValue).toArray();
            Map<Integer, Integer> rank = new HashMap<>();
            for (int i = 0; i < labels.length; i++) rank.put(labels[i], i);
            int[][] data = rows.toArray(new int[0][]);
            for (int[] row : data) for (int i = 0; i < row.length; i++) row[i] = rank.get(row[i]);
            return new Database(data, labels);
        }
    }

    interface Sink { void accept(BitSet pattern, int support) throws IOException; }
    static class LimitReached extends RuntimeException { }
    static class Miner {
        final Database db;
        final int minSupport;
        final Sink sink;
        final long stopAt;
        long closedCount, candidates, ppcRejected;
        Miner(Database db, int minSupport, Sink sink, double seconds) {
            if (minSupport < 1) throw new IllegalArgumentException("Minimum support must be positive");
            this.db = db; this.minSupport = minSupport; this.sink = sink;
            this.stopAt = System.nanoTime() + (long)(seconds * 1_000_000_000L);
        }
        void mine() throws IOException {
            if (minSupport > db.transactions.length) return;
            int[] tids = new int[db.transactions.length];
            for (int i = 0; i < tids.length; i++) tids[i] = i;
            extend(new BitSet(), tids, -1);
        }
        void checkTime() { if (System.nanoTime() > stopAt) throw new LimitReached(); }

        void extend(BitSet parent, int[] tids, int pivot) throws IOException {
            checkTime();
            // Occurrence delivery: scan only the supporting (conditional) database.
            int[] counts = new int[db.labels.length];
            for (int tid : tids) {
                if ((tid & 1023) == 0) checkTime();
                for (int item : db.transactions[tid]) counts[item]++;
            }
            BitSet closed = (BitSet)parent.clone();
            for (int item = 0; item < counts.length; item++) {
                if (counts[item] == tids.length) {
                    // PPC: a newly introduced item smaller than the pivot would
                    // duplicate a closed itemset reached through an earlier branch.
                    if (item < pivot && !parent.get(item)) { ppcRejected++; return; }
                    closed.set(item);
                }
            }
            // Empty closure is deliberately excluded from the output convention.
            if (!closed.isEmpty()) {
                closedCount++;
                if (sink != null) sink.accept(closed, tids.length);
            }
            // Build local vertical occurrence lists from horizontal transactions.
            // Keep frequent extension items only; closure items need no extension.
            int[][] occurrences = new int[counts.length][];
            for (int item = pivot + 1; item < counts.length; item++) {
                if (!closed.get(item) && counts[item] >= minSupport)
                    occurrences[item] = new int[counts[item]];
            }
            int[] positions = new int[counts.length];
            for (int tid : tids) {
                if ((tid & 1023) == 0) checkTime();
                for (int item : db.transactions[tid]) {
                    if (occurrences[item] != null)
                        occurrences[item][positions[item]++] = tid;
                }
            }
            for (int item = pivot + 1; item < occurrences.length; item++) {
                if (occurrences[item] == null) continue;
                candidates++;
                extend(closed, occurrences[item], item);
                occurrences[item] = null;
            }
        }
    }

    static int supportCount(String value, int n) {
        if (value.endsWith("%")) {
            double percentage = Double.parseDouble(value.substring(0, value.length() - 1));
            if (!Double.isFinite(percentage) || percentage <= 0 || percentage > 100)
                throw new IllegalArgumentException("Percentage must be in (0,100]");
            return Math.max(1, (int)Math.ceil(n * percentage / 100.0));
        }
        int count = Integer.parseInt(value);
        if (count < 1) throw new IllegalArgumentException("Support count must be positive");
        return count;
    }

    static void benchmark(Path folder, String percentages, int repeats, Path result) throws IOException {
        if (repeats < 1) throw new IllegalArgumentException("Repeats must be positive");
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) {
            paths.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(files::add);
        }
        if (files.isEmpty()) throw new IOException("No .txt datasets in " + folder);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(result))) {
            out.println("dataset,transactions,items,support_percent,min_support,closed_itemsets,median_mining_ms,min_mining_ms,max_mining_ms,load_ms,repeats,status");
            for (Path path : files) {
                long loadStart = System.nanoTime();
                Database db = Database.read(path);
                double loadMs = (System.nanoTime() - loadStart) / 1e6;
                for (String value : percentages.split(",")) {
                    value = value.trim();
                    int support = supportCount(value + "%", db.transactions.length);
                    long count = -1;
                    double[] times = new double[repeats];
                    boolean complete = true;
                    try {
                        // One untimed warm-up; avoid disk output while measuring mining.
                        new Miner(db, support, null, 30).mine();
                        for (int i = 0; i < repeats; i++) {
                            Miner miner = new Miner(db, support, null, 30);
                            long start = System.nanoTime();
                            miner.mine();
                            times[i] = (System.nanoTime() - start) / 1e6;
                            if (count >= 0 && count != miner.closedCount) throw new AssertionError("Unstable count");
                            count = miner.closedCount;
                        }
                    } catch (LimitReached e) { complete = false; }
                    if (complete) {
                        Arrays.sort(times);
                        double median = repeats % 2 == 1 ? times[repeats / 2] : (times[repeats / 2 - 1] + times[repeats / 2]) / 2;
                        out.printf(Locale.ROOT, "%s,%d,%d,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,OK%n",
                            path.getFileName(), db.transactions.length, db.labels.length, value, support, count,
                            median, times[0], times[repeats - 1], loadMs, repeats);
                        System.out.printf(Locale.ROOT, "%s | %s%% | closed=%d | median=%.3f ms%n", path.getFileName(), value, count, median);
                    } else {
                        out.printf(Locale.ROOT, "%s,%d,%d,%s,%d,,,,,%.3f,%d,TIMEOUT_30S_PER_RUN%n",
                            path.getFileName(), db.transactions.length, db.labels.length, value, support, loadMs, repeats);
                        System.out.println(path.getFileName() + " | " + value + "% | TIMEOUT");
                    }
                    out.flush();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Mine: java LCM <dataset.txt> <count OR percent%> [output.txt]");
            System.out.println("Bench: java LCM --benchmark <datasets-folder> [80,60,40] [3] [results.csv]");
            return;
        }
        if (args[0].equals("--benchmark")) {
            benchmark(Paths.get(args[1]), args.length > 2 ? args[2] : "80,60,40",
                args.length > 3 ? Integer.parseInt(args[3]) : 3,
                Paths.get(args.length > 4 ? args[4] : "results.csv"));
            return;
        }
        if (args.length < 2) throw new IllegalArgumentException("Dataset and support required");
        long loadStart = System.nanoTime();
        Database db = Database.read(Paths.get(args[0]));
        double loadMs = (System.nanoTime() - loadStart) / 1e6;
        int support = supportCount(args[1], db.transactions.length);
        PrintWriter writer = args.length > 2 ? new PrintWriter(Files.newBufferedWriter(Paths.get(args[2]))) : null;
        Sink sink = writer == null ? null : (pattern, count) -> {
            for (int item = pattern.nextSetBit(0); item >= 0; item = pattern.nextSetBit(item + 1))
                writer.print(db.labels[item] + " ");
            writer.println("#SUP: " + count);
            if (writer.checkError()) throw new IOException("Output write failed");
        };
        Miner miner = new Miner(db, support, sink, 300);
        long start = System.nanoTime();
        boolean complete = true;
        try { miner.mine(); } catch (LimitReached e) { complete = false; }
        finally { if (writer != null) writer.close(); }
        System.out.printf(Locale.ROOT, "Transactions=%d Items=%d MinSupport=%d Closed=%d LoadMs=%.3f MiningMs=%.3f Status=%s%n",
            db.transactions.length, db.labels.length, support, miner.closedCount, loadMs,
            (System.nanoTime() - start) / 1e6, complete ? "OK" : "TIMEOUT_PARTIAL_OUTPUT");
        if (!complete) System.exit(2);
    }
}
