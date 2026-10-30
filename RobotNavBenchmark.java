import java.io.*;
import java.util.*;

/public class RobotNavBenchmark {
    static final double INF = Double.POSITIVE_INFINITY;
    static long sink; // evita que el JIT elimine lecturas "muertas" de V0

    static final class City {
        int nInt, n, m, nStreets;
        double[] x, y;
        int[] eu, ev, edgeStreet;
        double[] ew;
        int[] streetU, streetV, streetFirstEdge;
    }

    static City makeCity(int W, int H, long seed) {
        Random r = new Random(seed);
        City c = new City();
        c.nInt = W * H;
        int maxStreets = 2 * W * H;
        int maxNodes = c.nInt + maxStreets * 6, maxEdges = maxStreets * 7;
        c.x = new double[maxNodes]; c.y = new double[maxNodes];
        c.eu = new int[maxEdges]; c.ev = new int[maxEdges]; c.ew = new double[maxEdges];
        c.edgeStreet = new int[maxEdges];
        c.streetU = new int[maxStreets]; c.streetV = new int[maxStreets]; c.streetFirstEdge = new int[maxStreets];
        for (int i = 0; i < H; i++)
            for (int j = 0; j < W; j++) {
                int id = i * W + j;
                c.x[id] = j * 100 + (r.nextDouble() - 0.5) * 30;
                c.y[id] = i * 100 + (r.nextDouble() - 0.5) * 30;
            }
        int nodes = c.nInt, edges = 0, st = 0;
        for (int i = 0; i < H; i++)
            for (int j = 0; j < W; j++)
                for (int d = 0; d < 2; d++) {
                    int ni = i + (d == 1 ? 1 : 0), nj = j + (d == 0 ? 1 : 0);
                    if (ni >= H || nj >= W) continue;
                    if (r.nextDouble() < 0.08) continue;
                    int u = i * W + j, v = ni * W + nj;
                    int k = r.nextInt(7);
                    c.streetU[st] = u; c.streetV[st] = v; c.streetFirstEdge[st] = edges;
                    int prev = u;
                    for (int t = 1; t <= k; t++) {
                        int nid = nodes++;
                        double f = t / (double) (k + 1);
                        c.x[nid] = c.x[u] + (c.x[v] - c.x[u]) * f + (r.nextDouble() - 0.5) * 8;
                        c.y[nid] = c.y[u] + (c.y[v] - c.y[u]) * f + (r.nextDouble() - 0.5) * 8;
                        edges = addEdge(c, edges, prev, nid, st);
                        prev = nid;
                    }
                    edges = addEdge(c, edges, prev, v, st);
                    st++;
                }
        c.n = nodes; c.m = edges; c.nStreets = st;
        return c;
    }

    static int addEdge(City c, int e, int a, int b, int street) {
        c.eu[e] = a; c.ev[e] = b; c.edgeStreet[e] = street;
        c.ew[e] = Math.hypot(c.x[b] - c.x[a], c.y[b] - c.y[a]);
        return e + 1;
    }

    static final class ONode {
        final int id; final double x, y;
        final float[] cloud = new float[64];
        final ArrayList<OEdge> out = new ArrayList<>(2);
        ONode(int id, double x, double y) { this.id = id; this.x = x; this.y = y; }
    }
    static final class OEdge {
        ONode from, to; double w; boolean blocked; OEdge rev;
        final float[] telemetry = new float[32];
        final double[] history = new double[16];
        String name;
    }
    static final class ObjGraph {
        ONode[] nodes; OEdge[] streetFwd;
    }

    static ObjGraph buildObj(City c, long seed) {
        Random r = new Random(seed);
        ObjGraph g = new ObjGraph();
        g.nodes = new ONode[c.n];
        for (int i = 0; i < c.n; i++) g.nodes[i] = new ONode(i, c.x[i], c.y[i]);
        g.streetFwd = new OEdge[c.nStreets];
        for (int e = 0; e < c.m; e++) {
            ONode a = g.nodes[c.eu[e]], b = g.nodes[c.ev[e]];
            OEdge f = new OEdge(), bk = new OEdge();
            f.from = a; f.to = b; bk.from = b; bk.to = a; f.rev = bk; bk.rev = f;
            f.w = bk.w = c.ew[e];
            String nm = "street-" + c.edgeStreet[e];
            f.name = bk.name = nm;
            for (int t = 0; t < 32; t++) { f.telemetry[t] = r.nextFloat(); bk.telemetry[t] = r.nextFloat(); }
            a.out.add(f); b.out.add(bk);
            if (c.streetFirstEdge[c.edgeStreet[e]] == e) g.streetFwd[c.edgeStreet[e]] = f;
        }
        return g;
    }

    interface Router {
        String name();
        void block(int street);
        void unblock(int street);
        double cost(int src, int dst);
    }

    static final class QE implements Comparable<QE> {
        final ONode n; final double d;
        QE(ONode n, double d) { this.n = n; this.d = d; }
        public int compareTo(QE o) { return Double.compare(d, o.d); }
    }

    static final class ObjRouter implements Router {
        final String name; final ObjGraph g; final boolean readTelemetry, recompute;
        ObjRouter(String name, ObjGraph g, boolean readTelemetry, boolean recompute) {
            this.name = name; this.g = g; this.readTelemetry = readTelemetry; this.recompute = recompute;
        }
        public String name() { return name; }
        public void block(int s) { OEdge e = g.streetFwd[s]; e.blocked = true; e.rev.blocked = true; }
        public void unblock(int s) { OEdge e = g.streetFwd[s]; e.blocked = false; e.rev.blocked = false; }
        public double cost(int src, int dst) {
            ONode S = g.nodes[src], T = g.nodes[dst];
            HashMap<ONode, Double> dist = new HashMap<>();
            HashSet<ONode> done = new HashSet<>();
            PriorityQueue<QE> pq = new PriorityQueue<>();
            dist.put(S, 0.0); pq.add(new QE(S, 0));
            while (!pq.isEmpty()) {
                QE q = pq.poll();
                if (!done.add(q.n)) continue;
                if (q.n == T) return q.d;
                for (OEdge e : q.n.out) {
                    if (e.blocked) continue;
                    double w = recompute ? Math.hypot(e.to.x - e.from.x, e.to.y - e.from.y) : e.w;
                    if (readTelemetry) { float s = 0; for (float f : e.telemetry) s += f; sink += (long) s; }
                    double nd = q.d + w;
                    Double old = dist.get(e.to);
                    if (old == null || nd < old) { dist.put(e.to, nd); pq.add(new QE(e.to, nd)); }
                }
            }
            return INF;
        }
    }

    static final class CSR {
        int n, m;
        int[] off, to, eid; float[] w; double[] x, y; boolean[] blocked;
    }

    static CSR buildCSR(int n, int m, int[] eu, int[] ev, double[] ew, double[] x, double[] y) {
        CSR g = new CSR();
        g.n = n; g.m = m;
        g.off = new int[n + 1];
        for (int e = 0; e < m; e++) { g.off[eu[e] + 1]++; g.off[ev[e] + 1]++; }
        for (int i = 0; i < n; i++) g.off[i + 1] += g.off[i];
        g.to = new int[2 * m]; g.eid = new int[2 * m]; g.w = new float[2 * m];
        int[] pos = Arrays.copyOf(g.off, n);
        for (int e = 0; e < m; e++) {
            int a = eu[e], b = ev[e];
            int pa = pos[a]++, pb = pos[b]++;
            g.to[pa] = b; g.eid[pa] = e; g.w[pa] = (float) ew[e];
            g.to[pb] = a; g.eid[pb] = e; g.w[pb] = (float) ew[e];
        }
        g.x = Arrays.copyOf(x, n); g.y = Arrays.copyOf(y, n);
        g.blocked = new boolean[m];
        return g;
    }

    static final class Heap {
        int[] nd = new int[1024]; double[] k = new double[1024]; int sz; int popNode; double popKey;
        void clear() { sz = 0; }
        void push(int node, double key) {
            if (sz == nd.length) { nd = Arrays.copyOf(nd, sz * 2); k = Arrays.copyOf(k, sz * 2); }
            int i = sz++;
            while (i > 0) { int p = (i - 1) >> 1; if (k[p] <= key) break; nd[i] = nd[p]; k[i] = k[p]; i = p; }
            nd[i] = node; k[i] = key;
        }
        void pop() {
            popNode = nd[0]; popKey = k[0]; sz--;
            if (sz > 0) {
                int node = nd[sz]; double key = k[sz]; int i = 0;
                while (true) {
                    int c = 2 * i + 1; if (c >= sz) break;
                    if (c + 1 < sz && k[c + 1] < k[c]) c++;
                    if (k[c] >= key) break;
                    nd[i] = nd[c]; k[i] = k[c]; i = c;
                }
                nd[i] = node; k[i] = key;
            }
        }
    }

    static final class CsrRouter implements Router {
        final String name; final CSR g; final boolean astar; final int[] streetEid; final int[] nodeMap;
        final double[] dist; final int[] stamp, closed, predNode, predArc; int cur; final Heap h = new Heap();
        int lastS, lastT;
        CsrRouter(String name, CSR g, boolean astar, int[] streetEid, int[] nodeMap) {
            this.name = name; this.g = g; this.astar = astar; this.streetEid = streetEid; this.nodeMap = nodeMap;
            dist = new double[g.n]; stamp = new int[g.n]; closed = new int[g.n];
            predNode = new int[g.n]; predArc = new int[g.n];
        }
        public String name() { return name; }
        public void block(int s) { g.blocked[streetEid[s]] = true; }
        public void unblock(int s) { g.blocked[streetEid[s]] = false; }
        double hh(int v, int t) {
            double dx = g.x[v] - g.x[t], dy = g.y[v] - g.y[t];
            return Math.sqrt(dx * dx + dy * dy);
        }
        public double cost(int src, int dst) {
            int s = nodeMap == null ? src : nodeMap[src], t = nodeMap == null ? dst : nodeMap[dst];
            lastS = s; lastT = t;
            cur++; h.clear();
            dist[s] = 0; stamp[s] = cur; h.push(s, astar ? hh(s, t) : 0);
            while (h.sz > 0) {
                h.pop();
                int u = h.popNode;
                if (closed[u] == cur) continue;
                closed[u] = cur;
                if (u == t) return dist[u];
                double du = dist[u];
                for (int a = g.off[u]; a < g.off[u + 1]; a++) {
                    if (g.blocked[g.eid[a]]) continue;
                    int v = g.to[a]; double nd = du + g.w[a];
                    if (stamp[v] != cur || nd < dist[v]) {
                        stamp[v] = cur; dist[v] = nd; predNode[v] = u; predArc[v] = a;
                        h.push(v, astar ? nd + hh(v, t) : nd);
                    }
                }
            }
            return INF;
        }
        int[] routeEdges() {
            int len = 0;
            for (int v = lastT; v != lastS; v = predNode[v]) len++;
            int[] r = new int[len]; int i = len;
            for (int v = lastT; v != lastS; v = predNode[v]) r[--i] = g.eid[predArc[v]];
            return r;
        }
    }

    static final class Contracted { CSR g; int[] newId; int[] streetEid; double ms; }

    @SuppressWarnings("unchecked")
    static Contracted contract(City c) {
        long t0 = System.nanoTime();
        HashMap<Integer, Double>[] nb = new HashMap[c.n];
        for (int i = 0; i < c.n; i++) nb[i] = new HashMap<>(4);
        for (int e = 0; e < c.m; e++) {
            Double ex = nb[c.eu[e]].get(c.ev[e]);
            if (ex == null || c.ew[e] < ex) { nb[c.eu[e]].put(c.ev[e], c.ew[e]); nb[c.ev[e]].put(c.eu[e], c.ew[e]); }
        }
        boolean[] removed = new boolean[c.n];
        for (int v = c.nInt; v < c.n; v++) {
            if (nb[v].size() != 2) continue;
            Iterator<Map.Entry<Integer, Double>> it = nb[v].entrySet().iterator();
            Map.Entry<Integer, Double> ea = it.next(), eb = it.next();
            int a = ea.getKey(), b = eb.getKey();
            double wab = ea.getValue() + eb.getValue();
            nb[a].remove(v); nb[b].remove(v);
            Double ex = nb[a].get(b);
            if (ex == null || wab < ex) { nb[a].put(b, wab); nb[b].put(a, wab); }
            removed[v] = true;
        }
        int[] newId = new int[c.n]; int kept = 0;
        for (int i = 0; i < c.n; i++) newId[i] = removed[i] ? -1 : kept++;
        int cnt = 0;
        for (int i = 0; i < c.n; i++) if (!removed[i]) for (int b : nb[i].keySet()) if (i < b) cnt++;
        int[] eu = new int[cnt], ev = new int[cnt]; double[] ew = new double[cnt];
        double[] nx = new double[kept], ny = new double[kept];
        int e = 0;
        for (int i = 0; i < c.n; i++) {
            if (removed[i]) continue;
            nx[newId[i]] = c.x[i]; ny[newId[i]] = c.y[i];
            for (Map.Entry<Integer, Double> en : nb[i].entrySet())
                if (i < en.getKey()) { eu[e] = newId[i]; ev[e] = newId[en.getKey()]; ew[e] = en.getValue(); e++; }
        }
        Contracted r = new Contracted();
        r.g = buildCSR(kept, cnt, eu, ev, ew, nx, ny);
        r.newId = newId;
        r.streetEid = new int[c.nStreets];
        for (int s = 0; s < c.nStreets; s++) {
            int a = newId[c.streetU[s]], b = newId[c.streetV[s]];
            int found = -1;
            for (int k = r.g.off[a]; k < r.g.off[a + 1]; k++) if (r.g.to[k] == b) { found = r.g.eid[k]; break; }
            if (found < 0) throw new IllegalStateException("macro-arista no encontrada para la calle " + s);
            r.streetEid[s] = found;
        }
        r.ms = (System.nanoTime() - t0) / 1e6;
        return r;
    }

    static double usedMB() {
        for (int i = 0; i < 3; i++) System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
    }

    static double pct(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    public static void main(String[] args) throws Exception {
        int W = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        int Q = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        double budgetMs = args.length > 2 ? Double.parseDouble(args[2]) : 10;

        City city = makeCity(W, W, 42);
        System.out.printf("Ciudad: %d intersecciones, %d nodos totales, %d calles, %d aristas no dirigidas%n",
                city.nInt, city.n, city.nStreets, city.m);

        double m0 = usedMB();
        ObjGraph og = buildObj(city, 1);
        double mObj = usedMB() - m0;
        double m1 = usedMB();
        CSR fine = buildCSR(city.n, city.m, city.eu, city.ev, city.ew, city.x, city.y);
        double mFine = usedMB() - m1;
        double m2 = usedMB();
        Contracted con = contract(city);
        double mCon = usedMB() - m2;
        System.out.printf("Preproceso offline (contraccion + CSR): %.0f ms | nodos tras contraer: %d (%.1f%%)%n",
                con.ms, con.g.n, 100.0 * con.g.n / city.n);
        System.out.printf("Memoria aprox.: objetos %.0f MB | CSR fino %.0f MB | CSR contraido %.0f MB%n", mObj, mFine, mCon);

        int[] streetEidFine = city.streetFirstEdge; // bloquear la primera arista de la calle
        Router[] routers = new Router[]{
                new ObjRouter("V0 original", og, true, true),
                new ObjRouter("V1 sin telemetria", og, false, true),
                new ObjRouter("V2 peso precalculado", og, false, false),
                new CsrRouter("V3 CSR", fine, false, streetEidFine, null),
                new CsrRouter("V4 CSR contraido", con.g, false, con.streetEid, con.newId),
                new CsrRouter("V5 A* contraido", con.g, true, con.streetEid, con.newId)
        };
        double[] memMB = {mObj, mObj, mObj, mFine, mCon, mCon};
        int[] nodesPer = {city.n, city.n, city.n, city.n, con.g.n, con.g.n};

        CsrRouter ref = (CsrRouter) routers[3];
        Random rq = new Random(7);
        double diam = Math.hypot(W * 100, W * 100);
        ArrayList<int[]> qs = new ArrayList<>(); ArrayList<Double> refCost = new ArrayList<>();
        long routeArcs = 0;
        for (int tries = 0; qs.size() < Q && tries < 200000; tries++) {
            int s = rq.nextInt(city.nInt), t = rq.nextInt(city.nInt);
            if (Math.hypot(city.x[s] - city.x[t], city.y[s] - city.y[t]) < 0.3 * diam) continue;
            if (ref.cost(s, t) == INF) continue;
            int[] route = ref.routeEdges();
            if (route.length < 6) continue;
            int street = city.edgeStreet[route[route.length / 3]];
            ref.block(street);
            double c1 = ref.cost(s, t);
            ref.unblock(street);
            if (c1 == INF) continue;
            qs.add(new int[]{s, t, street}); refCost.add(c1); routeArcs += route.length;
        }
        Q = qs.size();
        System.out.printf("Consultas validas: %d | tramos medios por ruta: %.0f | presupuesto: %.1f ms%n%n",
                Q, routeArcs / (double) Q, budgetMs);

        long[][] lat = new long[routers.length][Q];
        for (int ri = 0; ri < routers.length; ri++) {
            Router r = routers[ri];
            for (int pass = 0; pass < 2; pass++)                    // calentamiento del JIT
                for (int i = 0; i < Math.min(60, Q); i++) {
                    int[] q = qs.get(i); r.block(q[2]); r.cost(q[0], q[1]); r.unblock(q[2]);
                }
            for (int i = 0; i < Q; i++) {
                int[] q = qs.get(i);
                r.block(q[2]);
                long t0 = System.nanoTime();
                double c = r.cost(q[0], q[1]);
                lat[ri][i] = System.nanoTime() - t0;
                r.unblock(q[2]);
                double rc = refCost.get(i);
                if (Math.abs(c - rc) > 1e-4 * rc)
                    throw new IllegalStateException(r.name() + " da coste distinto en consulta " + i + ": " + c + " vs " + rc);
            }
            System.out.println("OK (mismos costes que la referencia): " + r.name());
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter("latency_nav.csv"))) {
            pw.println("version,query,time_ns");
            for (int ri = 0; ri < routers.length; ri++)
                for (int i = 0; i < Q; i++) pw.println(routers[ri].name() + "," + i + "," + lat[ri][i]);
        }
        System.out.printf("%n%-22s %8s %8s %8s %8s %9s %8s %9s %9s%n", "Version", "media", "p50", "p95", "p99", "max", ">budget", "x p50", "x p99");
        double base50 = 0, base99 = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter("summary_nav.csv"))) {
            pw.println("version,nodes,mem_mb,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,over_budget_pct");
            for (int ri = 0; ri < routers.length; ri++) {
                long[] s = lat[ri].clone(); Arrays.sort(s);
                double mean = Arrays.stream(s).average().orElse(0) / 1e6;
                double p50 = pct(s, 50) / 1e6, p95 = pct(s, 95) / 1e6, p99 = pct(s, 99) / 1e6, mx = s[s.length - 1] / 1e6;
                int over = 0; for (long v : s) if (v / 1e6 > budgetMs) over++;
                double overPct = 100.0 * over / s.length;
                if (ri == 0) { base50 = p50; base99 = p99; }
                System.out.printf("%-22s %8.3f %8.3f %8.3f %8.3f %9.3f %7.1f%% %8.1fx %8.1fx%n",
                        routers[ri].name(), mean, p50, p95, p99, mx, overPct, base50 / p50, base99 / p99);
                pw.printf(Locale.ROOT, "%s,%d,%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f%n",
                        routers[ri].name(), nodesPer[ri], memMB[ri], mean, p50, p95, p99, mx, overPct);
            }
        }
        System.out.println("\n(tiempos en ms; checksum ignorado: " + sink + ")");
    }
}
