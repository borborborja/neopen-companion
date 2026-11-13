#!/usr/bin/env python3
"""Compara JSON de Macrobenchmark del mismo dispositivo; no inventa métricas ausentes."""
import argparse
import json
from pathlib import Path
import statistics


def load(path):
    source = Path(path)
    paths = [source] if source.is_file() else sorted(source.rglob("*-benchmarkData.json"))
    if not paths:
        raise SystemExit(f"No hay JSON de Macrobenchmark en {source}")
    context, results = None, {}
    for item in paths:
        data = json.loads(item.read_text())
        context = data.get("context", context)
        for result in data.get("benchmarks", []):
            key = result.get("className", "") + "." + result["name"]
            values = {}
            for name, metric in result.get("metrics", {}).items():
                if "median" in metric:
                    values[name + ".median"] = metric["median"]
            for name, metric in result.get("sampledMetrics", {}).items():
                for percentile in ("P50", "P90", "P95", "P99"):
                    if percentile in metric:
                        values[name + "." + percentile.lower()] = metric[percentile]
                # Algunas versiones solo exportan muestras; la mediana sigue siendo observable.
                if not any(name + "." + p.lower() in values for p in ("P50", "P90", "P95", "P99")):
                    samples = [value for run in metric.get("runs", []) for value in run]
                    if samples:
                        values[name + ".median"] = statistics.median(samples)
            results[key] = values
    return context, results


def hardware(context):
    build = (context or {}).get("build", {})
    return {key: build.get(key) for key in ("brand", "device", "model", "fingerprint") if key in build}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("after")
    parser.add_argument("--output", type=Path, help="Guarda también la comparación estructurada")
    args = parser.parse_args()
    before_context, before = load(args.baseline)
    after_context, after = load(args.after)
    if hardware(before_context) != hardware(after_context):
        raise SystemExit("Los dispositivos no coinciden: no se pueden atribuir diferencias a la app.")
    rows = []
    for scenario in sorted(before.keys() & after.keys()):
        for metric in sorted(before[scenario].keys() & after[scenario].keys()):
            old, new = before[scenario][metric], after[scenario][metric]
            # Overrun puede cruzar cero: un porcentaje >100% no describe bien ese cambio.
            ratio = (new - old) / old * 100 if old > 0 and new >= 0 else None
            rows.append({"scenario": scenario, "metric": metric, "baseline": old, "after": new,
                         "delta": new - old, "change_percent": ratio})
    if not rows:
        raise SystemExit("No hay escenarios y métricas comunes para comparar.")
    print("| Escenario | Métrica | Antes | Después | Cambio |")
    print("|---|---|---:|---:|---:|")
    for row in rows:
        change = "—" if row["change_percent"] is None else f'{row["change_percent"]:+.1f}%'
        print(f'| {row["scenario"].rsplit(".", 1)[-1]} | {row["metric"]} | {row["baseline"]:.3f} | {row["after"]:.3f} | {change} |')
    missing = {"only_baseline": sorted(before.keys() - after.keys()), "only_after": sorted(after.keys() - before.keys())}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"baseline_context": before_context, "after_context": after_context,
                                          "comparison": rows, "unmatched_scenarios": missing}, indent=2) + "\n")


if __name__ == "__main__":
    main()
