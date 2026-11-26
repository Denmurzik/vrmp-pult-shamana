"""Analyze the four-condition study using anonymized, normalized session data."""

import argparse
from collections import defaultdict
from itertools import combinations
import json
from math import isfinite
from pathlib import Path
import re
from statistics import median

import pandas as pd
from scipy import stats


CONDITIONS = ("touch", "threshold", "dtw", "camera")
POSITIONS = (1, 2, 3, 4)
COUNTS = ("successes", "errors", "misses", "cancelled", "false_positives")
RATINGS = ("convenience", "control", "fatigue")
METRICS = (
    "time_ms", "success_rate", *COUNTS, "gesture_latency_ms", "raw_tlx", *RATINGS,
)


def holm(p_values):
    corrected = [0.0] * len(p_values)
    largest = 0.0
    for rank, index in enumerate(sorted(range(len(p_values)), key=p_values.__getitem__)):
        largest = max(largest, min(1.0, p_values[index] * (len(p_values) - rank)))
        corrected[index] = largest
    return corrected


def signed_rank(left, right):
    differences = (left - right).round(12)
    nonzero = differences[differences != 0]
    if not len(nonzero):
        return {"statistic": 0.0, "p": 1.0, "rank_biserial": 0.0}
    ranks = stats.rankdata(abs(nonzero))
    effect = (ranks[nonzero > 0].sum() - ranks[nonzero < 0].sum()) / ranks.sum()
    test = stats.wilcoxon(differences, method=stats.PermutationMethod(n_resamples=float("inf")))
    return {"statistic": float(test.statistic), "p": float(test.pvalue), "rank_biserial": float(effect)}


def compare_repeated(blocks, by, metric, levels):
    observations = [
        (row["participant_id"], row[by], row[metric])
        for row in blocks
        if row.get(metric) is not None
    ]
    table = pd.DataFrame(observations, columns=["participant_id", by, metric])
    table = table.pivot(index="participant_id", columns=by, values=metric)
    complete = table.reindex(columns=levels).dropna()
    result = {"n": len(complete), "friedman": None, "pairs": []}
    if len(complete) < 2:
        return result

    if complete.nunique(axis=1).eq(1).all():
        statistic, p = 0.0, 1.0
    else:
        test = stats.friedmanchisquare(*(complete[level] for level in levels))
        statistic, p = float(test.statistic), float(test.pvalue)
    result["friedman"] = {
        "statistic": statistic,
        "p": p,
        "kendall_w": statistic / (len(complete) * (len(levels) - 1)),
    }
    pairs = [
        {"left": a, "right": b, **signed_rank(complete[a].to_numpy(), complete[b].to_numpy())}
        for a, b in combinations(levels, 2)
    ]
    for pair, adjusted in zip(pairs, holm([pair["p"] for pair in pairs]), strict=True):
        pair["p_holm"] = adjusted
    result["pairs"] = pairs
    return result


def median_or_none(values):
    observed = [value for value in values if value is not None]
    return median(observed) if observed else None


def safe_rate(numerator, denominator):
    return numerator / denominator if denominator else None


def summarize_trials(times):
    return safe_rate(sum(time is not None for time in times), len(times)), median_or_none(times)


def paired_gap_test(gaps):
    if len(gaps) < 2:
        return None
    early, late = zip(*gaps, strict=True)
    return signed_rank(pd.Series(early).to_numpy(), pd.Series(late).to_numpy())


def learning_summary(blocks):
    observations = {condition: defaultdict(list) for condition in CONDITIONS}
    halves = {}
    for block in blocks:
        times = [row["success_ms"] for row in block.get("trials", [])]
        condition = block["condition"]
        for ordinal, result in enumerate(times, 1):
            observations[condition][ordinal].append(result)
        if len(times) >= 2:
            midpoint = len(times) // 2
            halves[block["participant_id"], condition] = (
                summarize_trials(times[:midpoint]),
                summarize_trials(times[midpoint:]),
            )
    curves = {}
    for condition, series in observations.items():
        curves[condition] = []
        for ordinal, times in sorted(series.items()):
            success_rate, time_ms = summarize_trials(times)
            curves[condition].append({
                "ordinal": ordinal, "n": len(times), "success_rate": success_rate, "median_time_ms": time_ms,
            })

    h5 = []
    for condition in CONDITIONS[1:]:
        time_gaps, rate_gaps = [], []
        for (participant, active_condition), phases in halves.items():
            if active_condition != condition or (participant, "touch") not in halves:
                continue
            (rate_early, time_early), (rate_late, time_late) = phases
            (touch_rate_early, touch_time_early), (touch_rate_late, touch_time_late) = halves[participant, "touch"]
            rate_gaps.append((touch_rate_early - rate_early, touch_rate_late - rate_late))
            if None not in (time_early, time_late, touch_time_early, touch_time_late):
                time_gaps.append((time_early - touch_time_early, time_late - touch_time_late))
        h5.append({
            "condition": condition,
            "time_n": len(time_gaps),
            "median_time_gap_early_ms": median_or_none([early for early, _ in time_gaps]),
            "median_time_gap_late_ms": median_or_none([late for _, late in time_gaps]),
            "median_time_gap_shrink_ms": median_or_none([early - late for early, late in time_gaps]),
            "time_test": paired_gap_test(time_gaps),
            "success_n": len(rate_gaps),
            "median_success_gap_early": median_or_none([early for early, _ in rate_gaps]),
            "median_success_gap_late": median_or_none([late for _, late in rate_gaps]),
            "median_success_gap_shrink": median_or_none([early - late for early, late in rate_gaps]),
            "success_test": paired_gap_test(rate_gaps),
        })
    tests = [row[key] for row in h5 for key in ("time_test", "success_test") if row[key] is not None]
    for test, adjusted in zip(tests, holm([test["p"] for test in tests]), strict=True):
        test["p_holm"] = adjusted
    return {"curves": curves, "h5": h5}


def validated_number(value, field, minimum=0, maximum=float("inf"), *, integer=False):
    if value is None:
        return None
    allowed = type(value) is int if integer else type(value) in (int, float)
    if not allowed or not isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"Invalid numeric value for {field}")
    return value


def normalize(input_data):
    if not isinstance(input_data, dict) or not isinstance(input_data.get("blocks"), list):
        raise ValueError("Expected a JSON object with a 'blocks' list")
    positions, conditions = set(), set()
    blocks = []
    for block in input_data["blocks"]:
        if not isinstance(block, dict):
            raise ValueError("Every block must be a JSON object")
        participant = block.get("participant_id")
        condition = block.get("condition")
        position = block.get("position")
        if not isinstance(participant, str) or not re.fullmatch(r"P(?:0[1-9]|1[0-2])", participant):
            raise ValueError("Expected an anonymous participant ID P01–P12")
        if condition not in CONDITIONS or type(position) is not int or position not in POSITIONS:
            raise ValueError(f"Invalid position or condition for {participant}")
        if (participant, position) in positions or (participant, condition) in conditions:
            raise ValueError(f"Duplicate position or condition for {participant}")
        positions.add((participant, position))
        conditions.add((participant, condition))
        complete = block.get("completed", True)
        if type(complete) is not bool:
            raise ValueError(f"Invalid completion status for {participant}")
        if not complete:
            continue

        trials = block.get("trials", [])
        if not isinstance(trials, list) or not all(isinstance(trial, dict) and "success_ms" in trial for trial in trials):
            raise ValueError(f"Expected trials with success_ms or null for {participant}")
        trials = [
            {"success_ms": validated_number(trial["success_ms"], "success_ms")}
            for trial in trials
        ]
        counts = {field: validated_number(block.get(field), field, integer=True) for field in COUNTS}
        success_rate, time_ms = summarize_trials([trial["success_ms"] for trial in trials])
        if not trials and counts["successes"] is not None and counts["misses"] is not None:
            success_rate = safe_rate(counts["successes"], counts["successes"] + counts["misses"])

        tlx = block.get("tlx")
        workload = None
        if tlx is not None:
            if not isinstance(tlx, list) or len(tlx) != 6:
                raise ValueError(f"Expected six NASA-TLX items for {participant}")
            scores = [validated_number(value, "tlx", maximum=100) for value in tlx]
            if None not in scores:
                workload = sum(scores) / len(scores)
        ratings = {field: validated_number(block.get(field), field, 1, 7, integer=True) for field in RATINGS}
        blocks.append({
            "participant_id": participant, "condition": condition, "position": position,
            "trials": trials, "time_ms": time_ms, "success_rate": success_rate,
            "gesture_latency_ms": validated_number(block.get("gesture_latency_ms"), "gesture_latency_ms"),
            "raw_tlx": workload, **counts, **ratings,
        })
    return blocks


def impressions(blocks):
    table = pd.DataFrame(blocks)
    if table.empty:
        return []
    result = []
    for condition, group in table.groupby("condition"):
        for rating, metric in [
            ("raw_tlx", "time_ms"), ("convenience", "time_ms"),
            ("control", "success_rate"), ("fatigue", "errors"),
        ]:
            paired = group[[rating, metric]].dropna()
            if len(paired) < 3 or paired.nunique().min() < 2:
                continue
            test = stats.spearmanr(paired[rating], paired[metric])
            result.append({
                "condition": condition, "rating": rating, "metric": metric, "n": len(paired),
                "rho": float(test.statistic), "p_exploratory": float(test.pvalue),
            })
    return result


def analyze(input_data):
    blocks = normalize(input_data)
    return {
        "blocks_used": len(blocks),
        "blocks_excluded": len(input_data["blocks"]) - len(blocks),
        "by_condition": {metric: compare_repeated(blocks, "condition", metric, CONDITIONS) for metric in METRICS},
        "by_position": {metric: compare_repeated(blocks, "position", metric, POSITIONS) for metric in METRICS},
        "learning": learning_summary(blocks),
        "impressions": impressions(blocks),
    }


def plot_learning(curves, destination):
    from matplotlib import pyplot as plt

    figure, axes = plt.subplots(2, 1, sharex=True, figsize=(8, 7), layout="constrained")
    for condition, series in curves.items():
        if series:
            ordinal = [row["ordinal"] for row in series]
            axes[0].plot(ordinal, [row["median_time_ms"] for row in series], "-o", label=condition)
            axes[1].plot(ordinal, [row["success_rate"] for row in series], "-o", label=condition)
    axes[0].set_ylabel("Медианное время (мс)")
    axes[1].set_ylabel("Доля успеха")
    axes[1].set_xlabel("Порядковый номер духа")
    axes[1].set_ylim(0, 1.05)
    for axis in axes:
        axis.grid(alpha=0.2)
        if axis.lines:
            axis.legend()
    figure.savefig(destination, dpi=160)
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("observations", type=Path)
    parser.add_argument("--plot", type=Path, help="Write learning curves as a PNG")
    args = parser.parse_args()
    try:
        report = analyze(json.loads(args.observations.read_text(encoding="utf-8")))
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    if args.plot:
        plot_learning(report["learning"]["curves"], args.plot)
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
