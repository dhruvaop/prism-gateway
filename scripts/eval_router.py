#!/usr/bin/env python3
"""One-command routing evaluation benchmark for Prism's 'auto' Smart Router.
Runs all cases in data/routing_eval.jsonl and reports accuracy and decision justifications.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.classifier import CLASSIFIER, evaluate_routing_set


def main():
    eval_file = ROOT / "data" / "routing_eval.jsonl"
    if not eval_file.exists():
        print(f"Error: {eval_file} not found.")
        sys.exit(1)

    cases = []
    with eval_file.open("r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line))

    results = evaluate_routing_set(cases)

    print("=" * 70)
    print("  PRISM SMART ROUTER ('model: auto') EVALUATION BENCHMARK")
    print("=" * 70)
    print(f"  Total Cases:     {results['total']}")
    print(f"  Correct:         {results['correct']}")
    print(f"  Accuracy:        {results['accuracy']:.2%}")
    baseline = results.get("baseline") or {}
    if baseline:
        print(f"  Length baseline: {baseline['correct']}/{results['total']} ({baseline['accuracy']:.2%}) at {baseline['threshold_chars']} chars")
        print(f"  Beats baseline:  {'YES' if results.get('beats_baseline') else 'NO'}")
    print("-" * 70)

    for case in results["cases"]:
        status = "PASS" if case["correct"] else "FAIL"
        print(f"  [{status}] {case['id']}: expected '{case['expected']}' -> actual '{case['actual']}'")
        print(f"         Reason: {case['reason']}")

    print("=" * 70)
    if results["accuracy"] >= 0.85 and results.get("beats_baseline", True):
        baseline = results.get("baseline") or {}
        print(f"SUCCESS: Router scored {results['accuracy']:.2%} vs length-only baseline {baseline.get('accuracy', 0):.2%}\n")
        sys.exit(0)
    else:
        print(f"FAILURE: Router scored {results['accuracy']:.2%} (<85%)\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
