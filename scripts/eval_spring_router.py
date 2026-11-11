#!/usr/bin/env python3
"""Print the routing evaluation produced by a running Spring Boot Prism gateway."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="Spring Boot gateway base URL")
    parser.add_argument("--token", default=os.environ.get("PRISM_ADMIN_TOKEN", ""), help="dashboard JWT, or set PRISM_ADMIN_TOKEN")
    args = parser.parse_args()
    headers = {"Authorization": f"Bearer {args.token}"} if args.token else {}
    request = urllib.request.Request(args.url.rstrip("/") + "/admin/router/eval", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
    except (OSError, urllib.error.URLError) as exc:
        print(f"Could not read router evaluation: {exc}", file=sys.stderr)
        return 2

    print(f"Routing evaluation: {result['correct']}/{result['total']} correct ({result['accuracy']:.1%})")
    baseline = result.get("baseline") or {}
    if baseline:
        print(f"Length-only baseline: {baseline['correct']}/{result['total']} ({baseline['accuracy']:.1%}) at {baseline['threshold_chars']} chars")
        print(f"Task router beats length-only baseline: {'YES' if result.get('beats_baseline') else 'NO'}")
    for case in result["cases"]:
        status = "PASS" if case["correct"] else "FAIL"
        task = f" task={case['task']}" if case.get("task") else ""
        print(f"  {status} {case['id']}: expected={case['expected']} actual={case['actual']}{task} — {case['note']}")
    return 0 if result["accuracy"] >= 0.9 and result.get("beats_baseline", True) else 1


if __name__ == "__main__":
    raise SystemExit(main())
