#!/usr/bin/env python3
"""Collect, print and diff macrobenchmark results.

Macrobenchmark writes a fairly verbose JSON per test class. This flattens it to
``{metric_name: median}``, which is the shape worth committing as a baseline and the shape
worth diffing.

Median, not mean, throughout: startup and frame timings have a long right tail (a background
service waking up mid-iteration adds tens of milliseconds), and a mean lets one unlucky
iteration masquerade as a regression. Macrobenchmark reports the median for exactly this
reason; taking it at its word keeps this script from inventing a second opinion.

Used only via ./perf.sh — see that script for the workflow.
"""

import json
import pathlib
import sys

# Metric-name suffixes decide which threshold applies and how a value is rendered.
# Macrobenchmark's names are e.g. "timeToInitialDisplayMs", "memoryRssAnonKb",
# "frameDurationCpuMs".
KIND_MEMORY = "memory"
KIND_FRAME = "frame"
KIND_STARTUP = "startup"


def classify(name: str) -> str:
    low = name.lower()
    if "memory" in low or low.endswith("kb"):
        return KIND_MEMORY
    if "frame" in low:
        return KIND_FRAME
    return KIND_STARTUP


def fmt(name: str, value: float) -> str:
    """Renders a raw metric value in the unit a human reasons about."""
    if classify(name) == KIND_MEMORY:
        # Reported in KB; megabytes are the unit anyone actually thinks in.
        return f"{value / 1024:8.1f} MB"
    return f"{value:8.1f} ms"


def collect(results_dir: str, out_path: str) -> None:
    """Flattens every *-benchmarkData.json under results_dir into one {name: median} map."""
    root = pathlib.Path(results_dir)
    files = sorted(root.rglob("*-benchmarkData.json"))
    if not files:
        sys.exit(
            f"ERROR: no benchmark output under {results_dir}.\n"
            "The run reported success but produced no data — usually means every test was\n"
            "filtered out. Check the --startup-only/--jank-only filter."
        )

    metrics: dict[str, float] = {}
    for f in files:
        data = json.loads(f.read_text())
        for bench in data.get("benchmarks", []):
            # e.g. "startupCold" + "timeToFullDisplayMs" -> "startupCold.timeToFullDisplayMs"
            test = bench.get("name", "?")

            # Single-value metrics: startup timings, memory, trace sections. Summarised by
            # macrobenchmark as a median across iterations.
            for metric_name, stats in bench.get("metrics", {}).items():
                if "median" not in stats:
                    continue
                metrics[f"{test}.{metric_name}"] = float(stats["median"])

            # Distribution metrics live in a SEPARATE field with percentile keys and no
            # "median" — frameDurationCpuMs and frameOverrunMs are here, not above. Reading
            # only "metrics" silently drops every frame-timing number while still producing a
            # plausible-looking report containing frameCount, which is how this was missed the
            # first time. For jank, P99 is the number that matters (see JankBenchmark), so the
            # tail percentiles are what get recorded.
            for metric_name, stats in bench.get("sampledMetrics", {}).items():
                for pct in ("P50", "P90", "P99"):
                    if pct in stats:
                        metrics[f"{test}.{metric_name}.{pct}"] = float(stats[pct])

    if not metrics:
        sys.exit("ERROR: benchmark output contained no metrics with a median.")

    out = pathlib.Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n")
    print(f"\nWrote {len(metrics)} metrics to {out_path}")


def show(path: str) -> None:
    metrics = json.loads(pathlib.Path(path).read_text())
    print()
    width = max(len(k) for k in metrics)
    for name in sorted(metrics):
        print(f"  {name:<{width}}  {fmt(name, metrics[name])}")
    print()


def diff(baseline_path: str, latest_path: str, t_startup: str, t_memory: str, t_frame: str) -> None:
    thresholds = {
        KIND_STARTUP: float(t_startup),
        KIND_MEMORY: float(t_memory),
        KIND_FRAME: float(t_frame),
    }
    baseline = json.loads(pathlib.Path(baseline_path).read_text())
    latest = json.loads(pathlib.Path(latest_path).read_text())

    names = sorted(set(baseline) | set(latest))
    width = max(len(n) for n in names)
    failures, added, removed = [], [], []

    print()
    for name in names:
        if name not in latest:
            # A metric that vanished is a silent loss of coverage, so it is reported rather
            # than skipped — but it is not a performance regression, so it does not fail.
            removed.append(name)
            continue
        if name not in baseline:
            added.append(name)
            print(f"  {name:<{width}}  {fmt(name, latest[name])}   (new)")
            continue

        was, now = baseline[name], latest[name]
        # abs() in the denominator, not a bare `was`. frameOverrunMs is legitimately negative
        # (a frame finishing 9ms before its deadline reads as -9.0), and dividing by a negative
        # baseline flips the sign of the change: drifting from -9.7ms to -8.0ms is a move
        # *toward* missing the deadline, but plain division reports it as a 17% improvement.
        # Dividing by the magnitude keeps "positive percent means worse" true for every metric.
        #
        # Zero baseline stays special-cased: any non-zero value is an infinite percentage,
        # which is true but not a useful thing to print.
        pct = ((now - was) / abs(was) * 100) if was else (0.0 if now == 0 else float("inf"))
        limit = thresholds[classify(name)]
        bad = pct > limit
        if bad:
            failures.append((name, was, now, pct, limit))
        mark = "FAIL" if bad else "ok"
        sign = "+" if pct >= 0 else ""
        pct_s = "  inf%" if pct == float("inf") else f"{sign}{pct:5.1f}%"
        print(f"  {name:<{width}}  {fmt(name, now)}  (was {fmt(name, was).strip()}, {pct_s})  {mark}")

    for name in removed:
        print(f"  {name:<{width}}  {'':>11}  (MISSING from this run)")

    print()
    if failures:
        print(f"{len(failures)} metric(s) regressed beyond threshold:")
        for name, was, now, pct, limit in failures:
            print(
                f"  {name}: {fmt(name, was).strip()} -> {fmt(name, now).strip()} "
                f"(+{pct:.1f}%, limit {limit:.0f}%)"
            )
        print()
        print("If this is an accepted trade-off, re-record with './perf.sh baseline'.")
        print("If it is not, the trace sections in the same output localise it: a jump in")
        print("Launcher.loadIcon or Launcher.storeLoad names the phase that got slower.")
        sys.exit(1)

    print("No regressions beyond threshold.")
    if removed:
        print(f"NOTE: {len(removed)} baseline metric(s) missing from this run — see above.")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "collect":
        collect(sys.argv[2], sys.argv[3])
    elif cmd == "show":
        show(sys.argv[2])
    elif cmd == "diff":
        diff(*sys.argv[2:7])
    else:
        sys.exit(f"unknown command: {cmd}")


if __name__ == "__main__":
    main()
