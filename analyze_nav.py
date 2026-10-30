import sys, json
import numpy as np, pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

BUDGET = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0
lat = pd.read_csv("latency_nav.csv")
summ = pd.read_csv("summary_nav.csv")

version_map = {
    "V0 original": "V0 Baseline",
    "V1 sin telemetria": "V1 No Telemetry",
    "V2 peso precalculado": "V2 Precomputed Weight",
    "V3 CSR": "V3 CSR Layout",
    "V4 CSR contraido": "V4 Contracted CSR",
    "V5 A* contraido": "V5 Contracted A*"
}

summ["version_en"] = summ["version"].map(lambda v: version_map.get(v, v))
lat["version_en"] = lat["version"].map(lambda v: version_map.get(v, v))

order_orig = list(summ["version"])
order_en = list(summ["version_en"])

COL = dict(zip(order_en, ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#6250d6"]))
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
plt.rcParams.update({"font.size": 9, "axes.edgecolor": GRID, "axes.labelcolor": MUTED,
                     "xtick.color": MUTED, "ytick.color": MUTED, "axes.spines.top": False,
                     "axes.spines.right": False, "figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb"})

short = {v: v.split(" ", 1)[0] + "\n" + v.split(" ", 1)[1] for v in order_en}

fig, ax = plt.subplots(figsize=(8.6, 4.2))
w = 0.26
x = np.arange(len(order_en))
for i, (col, lab, a) in enumerate([("p50_ms", "Median (p50)", 0.55), ("p99_ms", "p99", 0.8), ("max_ms", "Max", 1.0)]):
    vals = summ[col].values
    ax.bar(x + (i - 1) * w, vals, w - 0.03, color=[COL[v] for v in order_en], alpha=a, zorder=3)
    for xi, v in zip(x + (i - 1) * w, vals):
        ax.text(xi, v * 1.08, f"{v:.2f}", ha="center", va="bottom", fontsize=6.5, color=INK, rotation=90)
ax.axhline(BUDGET, color=INK, lw=1, ls="--", zorder=4)
ax.text(len(order_en) - 0.45, BUDGET * 1.08, f"Budget {BUDGET:g} ms", ha="right", fontsize=8, color=INK)
ax.set_yscale("log"); ax.set_xticks(x); ax.set_xticklabels([short[v] for v in order_en])
ax.set_ylabel("Replanning Latency (ms, log scale)")
ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
ax.grid(axis="y", color=GRID, zorder=0); ax.set_ylim(top=summ["max_ms"].max() * 3)
ax.set_title("Replanning Time After Street Blockage (bars: p50 light, p99 medium, max dark)", fontsize=9, color=INK, loc="left")
fig.tight_layout(); fig.savefig("figN_1_latencia.png", dpi=200); plt.close(fig)

fig, ax = plt.subplots(figsize=(8.0, 4.2))
for v_orig, v_en in zip(order_orig, order_en):
    t = np.sort(lat.loc[lat.version == v_orig, "time_ns"].values) / 1e6
    y = np.arange(1, len(t) + 1) / len(t)
    ax.step(t, y, where="post", color=COL[v_en], lw=1.8, label=v_en)
ax.axvline(BUDGET, color=INK, lw=1, ls="--")
ax.text(BUDGET * 0.93, 0.9, f"Budget {BUDGET:g} ms", fontsize=8, color=INK, ha="right")
ax.set_xscale("log"); ax.set_xlabel("Replanning Latency (ms, log scale)"); ax.set_ylabel("Fraction of Queries ≤ t")
ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
ax.grid(color=GRID); ax.legend(frameon=False, fontsize=8, loc="lower right")
ax.set_title("Empirical Cumulative Distribution Function (Tail Latency Critical for Safety)", fontsize=9, color=INK, loc="left")
fig.tight_layout(); fig.savefig("figN_2_ecdf.png", dpi=200); plt.close(fig)

fig, axs = plt.subplots(1, 2, figsize=(8.6, 3.6))
for ax, col, ttl in [(axs[0], "nodes", "Graph Node Count"), (axs[1], "mem_mb", "Approx. Memory Footprint (MB)")]:
    vals = summ[col].values
    ax.bar(x, vals, 0.6, color=[COL[v] for v in order_en], zorder=3)
    for xi, v in zip(x, vals):
        ax.text(xi, v * 1.02 + 0.001, f"{v:,.0f}", ha="center", va="bottom", fontsize=7, color=INK)
    ax.set_xticks(x); ax.set_xticklabels([v.split(" ")[0] for v in order_en]); ax.set_title(ttl, fontsize=9, color=INK, loc="left")
    ax.grid(axis="y", color=GRID, zorder=0)
fig.tight_layout(); fig.savefig("figN_3_tamano_memoria.png", dpi=200); plt.close(fig)