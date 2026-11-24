"""对 magpie 网关做模型测速：首字延迟 / 总耗时 / 输出速度（流式）。

用法：
    $env:MAGPIE_KEY = "sk-..."            # PowerShell（必填，不许写进代码）
    $env:MAGPIE_BASE = "http://127.0.0.1:3425/v1"   # 可选，默认就是这个
    python tools/bench_models.py [模型1 模型2 ...]

默认测一组常用模型；每个模型跑 2 次取中位数。结果打印成表。

注意：key 只从环境变量读。**任何凭据都不许写进仓库里的文件**——
写进去就等于把它发给了所有拿到这份代码的人（docs/28 P1-8）。
"""
import json
import os
import statistics
import sys
import time
import urllib.request

BASE = os.environ.get("MAGPIE_BASE", "http://127.0.0.1:3425/v1").rstrip("/") + "/chat/completions"
KEY = os.environ.get("MAGPIE_KEY", "")
PROMPT = "用一句话解释浏览器缓存，不要超过 60 字。"
MODELS = [
    "auto/glm-5.3",
    "auto/gpt-6.1-sol",
    "auto/deepseek-v4.1-flash",
    "auto/hy4-flash-free",
    "auto/qmodel_latest",
    "auto/kmodel_latest",
]
RUNS = 2
TIMEOUT = 40


def call(model):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": PROMPT}],
        "max_tokens": 96,
        "stream": True,
        "temperature": 0.3,
    }).encode("utf-8")
    req = urllib.request.Request(BASE, data=body, method="POST", headers={
        "Authorization": f"Bearer {KEY}",
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    })
    start = time.time()
    first = None
    chars = 0
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "ignore").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            try:
                obj = json.loads(payload)
                delta = obj.get("choices", [{}])[0].get("delta", {}).get("content") or ""
            except Exception:
                continue
            if delta:
                if first is None:
                    first = time.time() - start
                chars += len(delta)
    total = time.time() - start
    return first or 0.0, total, chars


def main():
    if not KEY:
        sys.exit(
            "缺少 MAGPIE_KEY。先设环境变量再跑（key 不许写进这个文件）：\n"
            '  PowerShell:  $env:MAGPIE_KEY = "sk-..."\n'
            "  bash:        export MAGPIE_KEY=sk-...\n"
            "  base 可用 MAGPIE_BASE 覆盖，默认 http://127.0.0.1:3425/v1",
        )
    models = sys.argv[1:] or MODELS
    print(f"{'model':32} {'first(med)':>10} {'total(med)':>11} {'chars':>6} {'chars/s':>8}  runs")
    for model in models:
        firsts, totals, chars = [], [], []
        errors = []
        for _ in range(RUNS):
            try:
                f, t, c = call(model)
                firsts.append(f)
                totals.append(t)
                chars.append(c)
            except Exception as exc:  # noqa: BLE001
                errors.append(type(exc).__name__ + ": " + str(exc)[:60])
        if not totals:
            print(f"{model:32} {'ERR':>10}  {errors[0] if errors else ''}")
            continue
        med_first = statistics.median(firsts)
        med_total = statistics.median(totals)
        med_chars = int(statistics.median(chars))
        speed = med_chars / med_total if med_total else 0
        print(f"{model:32} {med_first:9.2f}s {med_total:10.2f}s {med_chars:6d} {speed:7.1f}  "
              f"{len(totals)}/{RUNS}{'  ' + errors[0] if errors else ''}")


if __name__ == "__main__":
    main()