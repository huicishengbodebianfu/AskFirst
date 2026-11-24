#!/usr/bin/env python3
"""
假服务：给 AskFirst 做真机端到端验证（不需要真实 API Key）。

端口分工：
    8080  普通 SSE 流（9 段中文，80ms/段，[DONE]）—— 验传输层
    8081  429 限流 —— 验错误映射
    8082  先 sleep 25s 再 408 —— 验超时
    8083  **假引擎**：按轮次返回 AskFirst 的 JSON 契约 —— 验整条 App 流程（提问/出单）

设备侧接法（物理机）：
    adb reverse tcp:8080 tcp:8080
    adb reverse tcp:8083 tcp:8083
App 里 base_url 填 http://127.0.0.1:8083/v1（debug 构建已放行回环明文）

只依赖标准库，Windows / Linux 都能跑。
"""
import json
import os
import re
import sys
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHUNKS = [
    "我先", "问一个", "关键问题：", "你说的", "「慢」",
    "具体是", "打开就要", "四秒，", "还是滚动的时候卡？",
]

ENGINE_ROUNDS = [
    # round 1
    {
        "state": "gathering",
        "clarify": "你说的「慢」是首屏打开就慢，不是滚动卡。",
        "covered": [],
        "questions": [{
            "id": "tech_q2",
            "text": "时间、人力、预算，哪一条是硬的？",
            "why": "边界不清，候选方案没法比较",
            "kind": "open",
            "recommended": "我猜是时间和人力这两条最硬，对吗？",
            "options": [],
        }],
        "sheet_delta": [{"col": 1, "text": "{user10}", "source_message_ids": []}],
        "nudge": None,
    },
    # round 2
    {
        "state": "gathering",
        "clarify": "一个人力、两周内上线——那候选方案只能有一个。",
        "covered": ["tech_q1"],
        "questions": [{
            "id": "tech_q3",
            "text": "候选方案你心里有哪几个？把「什么都不做」也算一个。",
            "why": "不做往往是最便宜的基线",
            "kind": "choice",
            "recommended": "我猜你在想「加缓存」和「聚合接口」两个，对吗？",
            "options": ["只加本地缓存", "只做接口聚合", "两个都做"],
        }],
        "sheet_delta": [{"col": 2, "text": "{user10}", "source_message_ids": []}],
        "nudge": "再聊一个就能出单了",
    },
    # round 3+
    {
        "state": "ready",
        "clarify": "方案定了：先本地缓存，再聚合层。",
        "covered": ["tech_q1", "tech_q2", "tech_q4", "tech_q5"],
        "questions": [],
        "sheet_delta": [
            {"col": 4, "text": "{user10}", "source_message_ids": []},
            {"col": 4, "text": "{userTail}", "source_message_ids": []},
        ],
        "nudge": "够出单了",
    },
]

_engine_round = 0
_engine_lock = threading.Lock()

# 反向对照开关（docs/28 P0-2）：让它像"照契约办事但拿不到真实消息 id 的模型"那样，
# 往 source_message_ids 里塞编造的 id。修复前这些假 id 会原样进库；
# 修复后必须被白名单过滤掉（库里的出处只能指向真实 message.id）。
BOGUS_SOURCES = os.environ.get("FAKE_BOGUS_SOURCES") == "1"
_BOGUS_IDS = ["msg_fabricated_1", "msg_fabricated_2"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("content-length") or 0)
        body = self.rfile.read(length) if length else b""
        body_text = body.decode("utf-8", "ignore")
        try:
            payload = json.loads(body_text or "{}")
        except Exception:
            payload = {}
        wants_stream = bool(payload.get("stream"))
        port = self.server.server_address[1]

        if port == 8081:
            self._json(429, {"error": {"message": "Rate limit exceeded", "code": 429}})
            return
        if port == 8082:
            time.sleep(25)
            self._json(408, {"error": {"message": "上游超时", "code": 408}})
            return
        if port == 8083:
            self._dump_request(body_text)
            # M6 调研：两份提示词的标记不一样，靠它分辨「立题」还是「对照」
            if "立题" in body_text:
                self._reply(wants_stream, self._research_questions_payload())
            elif "取证员" in body_text or "四种判定" in body_text:
                self._reply(wants_stream, self._research_verdict_payload(body_text))
            # 方案文档（M5）：请求里有「文档骨架」段就是方案生成
            elif "文档骨架" in body_text or "这一次只补下面这几节" in body_text:
                self._reply(wants_stream, self._plan_payload(body_text))
            elif "已实时填进去的条目" in body_text:
                self._reply(wants_stream, self._sheet_payload(body_text))
            else:
                self._reply(wants_stream, self._engine_payload(body_text))
            return
        if port == 8084:
            # 只发头 + 一条注释就挂住：用来测"引擎单轮 75s 预算"
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            self.wfile.write(b": stalled\n\n")
            self.wfile.flush()
            time.sleep(300)
            return
        if port == 8085:
            # 迟迟不回头：用来测 ping 的 20s 超时
            time.sleep(40)
            self._json(408, {"error": {"message": "太慢了", "code": 408}})
            return

        if not wants_stream:
            self._json(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": "pong"}}]})
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        for text in CHUNKS:
            frame = {"id": "fake-1", "choices": [{"index": 0, "delta": {"content": text}}]}
            self.wfile.write(f"data: {json.dumps(frame, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.08)
        finish = {"id": "fake-1", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
        self.wfile.write(f"data: {json.dumps(finish, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    # ---------------------------------------------------------------- helpers

    def _dump_request(self, body_text):
        """把请求体落到工作区的 artifacts 里（验「引用摘要真的进上下文了」这类事）。"""
        dump_dir = os.environ.get("FAKE_DUMP_DIR")
        if not dump_dir:
            return
        try:
            path = os.path.join(dump_dir, "fake-last-request.json")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(body_text)
            marker = "## 引用的旧确认单摘要"
            if marker in body_text:
                tail = body_text.split(marker, 1)[1]
                lines = [line for line in tail.splitlines() if line.strip()][:5]
                print("[fake] citation block in context: " + " | ".join(lines), flush=True)
            if "## 文档骨架" in body_text:
                print("[fake] plan document request seen", flush=True)
        except Exception as exc:  # 落盘失败不影响回复
            print(f"[fake] dump failed: {exc}", flush=True)

    def _reply(self, wants_stream, obj):
        payload = json.dumps(obj, ensure_ascii=False)
        if not wants_stream:
            self._json(200, obj)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        for i in range(0, len(payload), 48):
            frame = {"choices": [{"index": 0, "delta": {"content": payload[i:i + 48]}}]}
            self.wfile.write(f"data: {json.dumps(frame, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.02)
        finish = {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
        self.wfile.write(f"data: {json.dumps(finish, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _json(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(raw)

    def _last_user_text(self, body_text):
        """从 App 发来的上下文块里取出"用户最后一句回答"。

        注意：App 发给模型的是**一整块上下文**（一个 user 消息），真正的回答在
        `## 会话全文` 段里以 `[U] …` 的形式出现。假引擎要回填出处，就必须取这一行。
        """
        try:
            data = json.loads(body_text)
        except Exception:
            return "首屏打开就要四秒"
        for message in reversed(data.get("messages", [])):
            if message.get("role") != "user":
                continue
            content = message.get("content") or ""
            marker = "\n[U] "
            index = content.rfind(marker)
            if index >= 0:
                tail = content[index + len(marker):]
                for stop in ("\n[A] ", "\n\n##", "\n\n"):
                    cut = tail.find(stop)
                    if cut >= 0:
                        tail = tail[:cut]
                return tail.strip()[:60]
            return content[:60]
        return "首屏打开就要四秒"

    def _engine_payload(self, body_text):
        global _engine_round
        with _engine_lock:
            _engine_round += 1
            round_no = _engine_round
        index = min(round_no, len(ENGINE_ROUNDS)) - 1
        print(f"[fake-engine] round={round_no} -> payload#{index + 1}", flush=True)
        template = json.loads(json.dumps(ENGINE_ROUNDS[index]))
        user = self._last_user_text(body_text)
        print(f"[fake-engine]   last user answer = {user!r}", flush=True)
        user = self._last_user_text(body_text)
        filled = []
        for delta in template.get("sheet_delta", []):
            text = delta["text"]
            text = (
                text.replace("{user10}", user[:10])
                .replace("{userTail}", user[-9:].strip())
                .replace("{user6b}", user[3:9])
                .replace("{user6}", user[:6])
            )
            if text.strip():
                filled.append({
                    "col": delta["col"],
                    "text": text,
                    # 开着反向对照时故意给编造的 id（模拟"模型看不到真实消息 id 只能瞎编"）
                    "source_message_ids": _BOGUS_IDS if BOGUS_SOURCES else delta.get("source_message_ids", []),
                })
        template["sheet_delta"] = filled
        return template

    def _sheet_payload(self, body_text):
        return {
            "summary": "首屏缓存分层",
            "columns": {
                "1": ["首屏 P90 从 4s 降到 1.2s"],
                "2": ["一个人力，两周内上线"],
                "3": ["选定：本地缓存 + 聚合层", "放弃：只上 CDN（预算不够）"],
                "4": ["今天：拉首屏接口耗时火焰图", "本周：定聚合层接口契约"],
                "5": ["还没定：缓存失效用 TTL 还是主动通知"],
            },
        }

    # ------------------------------------------------------------ M6 调研

    def _research_questions_payload(self):
        """立题：3 个可检索问题。

        关键词里刻意带上假 GitHub 服务的开关词（常见做法 / 反模式 / 已知坑 / 无人区），
        这样一次真机验收就能同时撞出四种判定——**这是测试数据的手法，不是产品行为**：
        真模型给的关键词是「Room FTS5」这种技术名词。
        """
        return {
            "questions": [
                {
                    "text": "Compose 长列表重排后怎么保持滚动位置",
                    "keywords": ["常见做法", "LazyList state"],
                    "why": "你的方案要重排确认单列表",
                },
                {
                    "text": "把确认单存成单个 JSON 会不会被并发写坏",
                    "keywords": ["反模式", "已知坑"],
                    "why": "你的方案要把整份确认单塞进一个字段",
                },
                {
                    "text": "中文分词这块在 GitHub 上是不是一片无人区",
                    "keywords": ["无人区"],
                    "why": "你确认单里提过本地搜索",
                },
            ],
        }

    def _research_verdict_payload(self, body_text):
        """对照：从请求里读回真实证据 id，按证据造四种判定（不编 id、不编链接）。

        这样真机验收才有意义——报告里出现的每一条引文与链接，都是这次真抓到的。
        """
        text = body_text.replace("\\n", "\n")
        questions = self._research_questions(text)
        evidence = self._research_evidence(text)

        issues = [item for item in evidence if item["kind"] == "issue"]
        repos = [item for item in evidence if item["kind"] == "repo"]
        verdicts = []

        if issues:
            first = issues[0]
            verdicts.append({
                "question": first["question"],
                "judgement": "antipattern",
                "why": "有项目明确放弃了你打算用的做法，理由写在原话里。",
                "quote": (first["snippet"] or "moved to per-record rows")[:120],
                "quote_url": first["url"],
                "evidence": [first["id"]],
            })
        if len(issues) >= 2:
            verdicts.append({
                "question": issues[0]["question"],
                "judgement": "known_pit",
                "why": "至少两个项目在同一个地方摔过，你的方案里没有这一步。",
                "quote": "",
                "quote_url": "",
                "evidence": [issues[0]["id"], issues[1]["id"]],
            })
        if len(repos) >= 3:
            verdicts.append({
                "question": repos[0]["question"],
                "judgement": "common",
                "why": "≥3 个独立项目在做同一件事，你不是孤例。",
                "quote": "",
                "quote_url": "",
                "evidence": [repo["id"] for repo in repos[:3]],
            })
        # 哪个问题一条证据都没有，就对它下「无人区」（复核过才会保留这个判定）
        covered = {item["question"] for item in evidence}
        for question in questions:
            if question["idx"] in covered:
                continue
            verdicts.append({
                "question": question["idx"],
                "judgement": "unexplored",
                "why": "GitHub 内换词复核后仍然没有同类，这块没人验证过。",
                "quote": "",
                "quote_url": "",
                "evidence": [],
            })
            break

        print(f"[fake-research] evidence={len(evidence)} verdicts={len(verdicts)}", flush=True)
        return {"verdicts": verdicts}

    def _research_questions(self, text):
        """从对照请求里读回问题清单（`1. 问题（关键词：a / b）`）。"""
        out = []
        marker = "## 要对照的问题"
        if marker not in text:
            return out
        tail = text.split(marker, 1)[1]
        for line in tail.splitlines():
            matched = re.match(r"^\s*(\d+)\.\s*(.+?)（关键词：", line)
            if matched:
                out.append({"idx": int(matched.group(1)), "text": matched.group(2).strip()})
            elif out and not line.strip():
                break
        return out

    def _research_evidence(self, text):
        """从对照请求里读回证据块：`[id] kind …` / `  url: …` / `  内容: …`。"""
        out = []
        current = None
        for line in text.splitlines():
            head = re.match(r"^\[(ev_[^\]]+)\]\s+(\w+)", line)
            if head:
                current = {
                    "id": head.group(1),
                    "kind": head.group(2),
                    "url": "",
                    "snippet": "",
                    "question": int(re.search(r"_q(\d+)_", head.group(1)).group(1)),
                }
                out.append(current)
                continue
            if current is None:
                continue
            url = re.match(r"^\s+url:\s*(\S+)", line)
            if url:
                current["url"] = url.group(1)
                continue
            snippet = re.match(r"^\s+内容:\s*(.+)$", line)
            if snippet:
                current["snippet"] = snippet.group(1).strip()
        return [item for item in out if item["url"]]


    def _plan_payload(self, body_text):
        """假方案：按请求里的骨架逐节造，出处只挑请求里真出现过的消息 id。

        三种模式（环境变量）：
            FAKE_PLAN_PARTIAL=1  第一次只给前 2 节（验「接着写剩下的」）
            FAKE_PLAN_SOFTEN=1   「待决问题」写成漂亮话（验 blocking 拦截）
        """
        global _plan_calls
        with _plan_lock:
            _plan_calls += 1
            call_no = _plan_calls

        skeleton = self._headings_after(body_text, "## 文档骨架")
        missing = self._headings_after(body_text, "## 这一次只补下面这几节")
        headings = missing or skeleton
        ids = self._message_ids(body_text)
        if not ids:
            ids = ["m_unknown"]

        print(f"[fake-plan] call={call_no} skeleton={skeleton} missing={missing} ids={ids}", flush=True)

        soften = os.environ.get("FAKE_PLAN_SOFTEN") == "1"
        stray = os.environ.get("FAKE_PLAN_STRAY") == "1"
        partial = os.environ.get("FAKE_PLAN_PARTIAL") == "1" and call_no == 1

        sections = []
        for index, heading in enumerate(headings):
            source = ids[index % len(ids)]
            if "待决" in heading:
                body = (
                    "后续持续优化缓存命中率，逐步完善监控与告警。"
                    if soften
                    else "缓存上限怎么定（TTL 还是主动通知）还没想清楚，先不定。"
                )
            elif stray and index == 1:
                # 故意写一个原话里没有的数字：验「硬答案保护」的提醒
                body = "计划 3 个月内上线，先把首屏 4 秒搞到 1 秒。"
            elif index == 0:
                body = f"这一场从头到尾都在解决同一件事：{heading}。原话里说的是首屏慢，不是滚动卡。"
            else:
                body = f"{heading}：按他原话整理，没有新增内容。"
            sections.append({"heading": heading, "body": body, "sources": [source]})

        if partial:
            sections = sections[:2]

        title = "首屏缓存分层方案" if not missing else ""
        return {"title": title, "sections": sections}

    def _headings_after(self, body_text, marker):
        """取 marker 之后那一串 `1. 标题` 行（请求体是 JSON，先把 \n 还原成换行）。"""
        text = body_text.replace("\\n", "\n")
        index = text.find(marker)
        if index < 0:
            return []
        tail = text[index + len(marker):]
        headings = []
        for line in tail.splitlines():
            stripped = line.strip()
            if not stripped:
                if headings:
                    break
                continue
            matched = re.match(r"^\d+[.、]\s*(.+)$", stripped)
            if matched:
                headings.append(matched.group(1).strip())
            elif headings:
                break
        return headings

    def _message_ids(self, body_text):
        seen = []
        for matched in re.finditer(r"\[(m_[0-9a-zA-Z]+)\]", body_text):
            if matched.group(1) not in seen:
                seen.append(matched.group(1))
        return seen


_plan_calls = 0
_plan_lock = threading.Lock()


def serve(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    print(f"[fake-sse] listening on 127.0.0.1:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    ports = [int(p) for p in sys.argv[1:]] or [8080, 8081, 8082, 8083, 8084, 8085]
    for p in ports:
        threading.Thread(target=serve, args=(p,), daemon=True).start()
    print("[fake-sse] ready", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
