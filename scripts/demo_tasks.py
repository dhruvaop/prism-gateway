#!/usr/bin/env python3
"""Demonstrate Prism's classification-based auto routing.

Sends one prompt per task category with ``model: "auto"`` and prints the
classification from the ``x-prism-task`` response header, the tier, the serving
provider/model, cache status, and a short excerpt of the answer.

Usage:
    python scripts/demo_tasks.py --url http://localhost:8080 --key prism-sk-research-4d5e6f

The research key is used by default because its cache is disabled and it allows
``auto``. Any seeded key also allows ``auto``. With OpenRouter configured as the
primary provider, each task is served by its configured task model; otherwise
the deterministic Alpha/Beta mocks answer and the script still verifies the
classification and tier.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

CASES = [
    ("web_search", "Search the web for the latest news about the NVIDIA earnings release."),
    ("research", "Research the state of the art in vector search and cite your sources."),
    ("reasoning", "Prove that the square root of 2 is irrational."),
    ("code", "Implement a Python function that parses this CSV and add unit tests."),
    ("simple", "What is the capital of France?"),
]


def post_chat(base, key, model, prompt):
    body = {"model": model, "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request(
        base.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"error": {"message": raw}}
        return e.code, {k.lower(): v for k, v in e.headers.items()}, parsed


def excerpt(body):
    try:
        text = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        return ""
    text = " ".join(str(text).split())
    return text[:110] + ("..." if len(text) > 110 else "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="gateway base URL, e.g. http://localhost:8080")
    parser.add_argument("--key", default="prism-sk-research-4d5e6f", help="a virtual key that allows auto")
    parser.add_argument("--model", default="auto", help="model alias (default: auto)")
    args = parser.parse_args()

    salt = uuid.uuid4().hex[:8]
    failures = 0
    print(f"\nTask routing demo against {args.url} with key {args.key[:18]}...\n")
    for expected, prompt in CASES:
        status, headers, body = post_chat(args.url, args.key, args.model, f"({salt}) {prompt}")
        task = headers.get("x-prism-task", "-")
        tier = headers.get("x-prism-provider", "-")
        model = "-"
        try:
            model = body["choices"][0].get("model", "-")
        except (KeyError, IndexError, TypeError):
            pass
        ok = status == 200 and task == expected
        if not ok:
            failures += 1
        print(f"  {'PASS' if ok else 'FAIL'}  expected={expected:<10} got={task:<10} "
              f"provider={tier:<40} model={model:<45} cache={headers.get('x-prism-cache', '-')}")
        print(f"        prompt: {prompt}")
        if status == 200:
            print(f"        answer: {excerpt(body)}")
        else:
            print(f"        error:  {json.dumps(body)[:160]}")
        print()

    print("=" * 60)
    print(f"  {len(CASES) - failures} passed, {failures} failed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
