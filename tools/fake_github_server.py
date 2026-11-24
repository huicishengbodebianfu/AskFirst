#!/usr/bin/env python3
"""
假服务：GitHub REST API 的本机替身，给 AskFirst 的 M6「调研验证器」做真机验收。

端口分工（全部端点共用这一个端口）：
    8090  /search/repositories  /search/issues  /search/code
          /repos/{owner}/{repo}/readme  /rate_limit
          —— 路径、字段名、状态码都跟真实 api.github.com 一致，App 客户端只在
             base_url 上换一行，不需要改 Retrofit/OkHttp 的任何代码

观测端点（GitHub 没有，纯粹为本机取证）：
    GET  /__stats   最近请求流水（路径 / 查询串 / 是否带 Authorization / 状态码）+ 计数
    POST /__reset   清空流水，方便一次验收只留一次证据

设备侧接法（物理机，USB 连着本机）：
    adb reverse tcp:8090 tcp:8090
    App 里 GitHub base_url 填 http://127.0.0.1:8090（debug 构建已放行回环明文）
    token 随便填个非空值：/search/code 必须带 Authorization 头，真实 GitHub 对匿名
    代码搜索就是 401，这里刻意保持一致，验的就是 App 的「没 token 时降级」分支

为什么默认绑 0.0.0.0：
    adb reverse 把手机的 127.0.0.1:8090 转到本机回环；但模拟器走 10.0.2.2、或者手机
    直接连同一 Wi-Fi 打到本机 IP 时，流量落的是网卡地址，127.0.0.1 收不到。
    所以默认 0.0.0.0:8090，要收紧就用 --host 127.0.0.1。

只依赖标准库，Windows（`python fake_github_server.py` 直接跑）/ Linux 都行。
各个验收开关怎么用 q 里的关键词触发，见同目录 fake_github_server.md。
"""
import argparse
import base64
import json
import re
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8090

JSON_CT = "application/json; charset=utf-8"

# 观测流水只留最近这么多条：验收一轮几十个请求，留 500 条足够，又不会一直涨内存。
MAX_RECORDS = 500

# ---------------------------------------------------------------------------
# 关键词开关
#
# 验收要能「确定性复现」四种判定和三种失败模式，靠的就是 q 里的这些词。
# 顺序即优先级：前一个命中就不再往后看（比如 q 同时带「无人区」和「反模式」时，
# 「无人区」优先——空结果是更强的断言，先验它）。
# 每个开关存在的理由写在 _mode() 的注释里。
# ---------------------------------------------------------------------------
SWITCHES = [
    ("empty", ("无人区", "nobody")),
    ("ratelimit", ("429",)),
    ("badjson", ("坏json", "badjson")),
    ("slow", ("超时", "slow")),
    ("partial", ("半个", "partial")),
    ("antipattern", ("反模式", "antipattern")),
    ("pitfall", ("已知坑", "pitfall")),
    ("common", ("常见做法", "common")),
]


# ---------------------------------------------------------------------------
# 假数据
#
# 时间戳写死（不用 now()）：验收会截图取证，固定时间戳才能两次跑出同一份证据。
# ---------------------------------------------------------------------------

def _repo(full_name, description, stars, language, pushed_at, archived=False, branch="main"):
    return {
        "full_name": full_name,
        "html_url": "https://github.com/" + full_name,
        "description": description,
        "stargazers_count": stars,
        "pushed_at": pushed_at,
        "default_branch": branch,
        "archived": archived,
        "language": language,
    }


def _issue(repo, number, title, body, state, created_at):
    return {
        "html_url": "https://github.com/%s/issues/%d" % (repo, number),
        "title": title,
        "body": body,
        "state": state,
        "created_at": created_at,
        "repository_url": "https://api.github.com/repos/" + repo,
    }


# 通用数据：任何没命中的关键词都走这套，保证 App 任何时候都不会「空手」判定，
# 也就不会因为服务端没数据而误判成「无人区」。
GENERAL_REPOS = [
    _repo("phrase/offline-first-notes",
          "离线优先的笔记 App：Room 按记录存行，自带 Markdown 导出",
          4210, "Kotlin", "2026-09-01T00:00:00Z"),
    _repo("kizzy/cache-dojo",
          "Android 本地缓存几种写法的横向压测：单文件 JSON 对比按记录存行",
          1837, "Kotlin", "2026-07-18T00:00:00Z"),
    _repo("markdown-lab/md-export-kit",
          "Markdown 导出工具链：GFM 表格转义、大文档分片写盘",
          962, "TypeScript", "2026-05-30T00:00:00Z"),
    _repo("droid-rooms/room-migration-samples",
          "Room 迁移样例集，含从单表 JSON 字段迁到关系表的完整脚本",
          3184, "Kotlin", "2026-08-11T00:00:00Z"),
    _repo("openconf/decision-sheet",
          "确认单 / 决策表模板：一页写清候选方案与取舍",
          744, "Python", "2026-02-09T00:00:00Z"),
    _repo("searchops/github-cache-proxy",
          "GitHub 检索结果本地缓存代理，处理 401 / 429 / 部分成功",
          512, "Go", "2026-04-22T00:00:00Z"),
]

# 「常见做法」命中时用：6 个独立项目、高 star、做法一致，用来验「≥3 个独立项目一致」。
COMMON_REPOS = [
    _repo("acme-mobile/field-notes",
          "本地优先的现场记录 App：Room 按记录存行，导出 Markdown 走分片",
          12840, "Kotlin", "2026-09-12T00:00:00Z"),
    _repo("bramble/android-offline-sync",
          "Android 离线同步模板：先落本地库再补同步，导出 GFM 表格",
          21457, "Kotlin", "2026-08-03T00:00:00Z"),
    _repo("northwind/localfirst-android",
          "Local-first 实践样板：SQLite 行存 + 可重放导出，弱网也能看历史",
          8320, "Java", "2026-06-25T00:00:00Z"),
    _repo("paperclip/cache-and-export",
          "缓存与导出分离的参考实现，导出 Markdown 前先做表格转义",
          46210, "Kotlin", "2026-09-20T00:00:00Z"),
    _repo("sunlit/room-recipes",
          "Room 惯用法手册：一行一条记录，拒绝整份 JSON blob",
          15936, "Kotlin", "2026-07-07T00:00:00Z"),
    _repo("vela/markdown-outbox",
          "Markdown 导出队列：超长正文分片写、失败可断点续传",
          6715, "Rust", "2026-05-16T00:00:00Z"),
]

# 「反模式」命中时用：3 条明确抱怨「整份塞一个 JSON」的 issue，
# 正文里带原话，App 要能把原话摘出来引用——所以句子必须短、可整句引用。
ANTIPATTERN_ISSUES = [
    _issue("kizzy/cache-dojo", 217,
           "Single JSON blob cache caused corruption after process kill",
           "We stored the whole draft as one JSON file. A kill mid-write left it "
           "truncated and it caused corruption across every thread — a week of notes "
           "gone. We moved to per-record rows in SQLite and never looked back.",
           "closed", "2026-03-02T00:00:00Z"),
    _issue("phrase/offline-first-notes", 402,
           "整份会话存单个 JSON 文件，断电后整库读不出来",
           "写盘写到一半进程被杀，启动时 json.load 直接抛 JSONDecodeError，只能整份丢弃。"
           "这个做法 caused corruption 不止我们一家；后来 moved to per-record rows，"
           "一条坏了只坏一条。迁移脚本在 scripts/0007_split_blob.py。",
           "closed", "2026-01-19T00:00:00Z"),
    _issue("droid-rooms/room-migration-samples", 88,
           "Serialize-everything-on-save is a footgun in the field",
           "Saving one big object on every edit meant any partial write caused corruption "
           "of the entire history. After two field incidents we moved to per-record rows; "
           "write amplification went up, data loss went to zero. Worth it.",
           "closed", "2025-11-08T00:00:00Z"),
]

# 「已知坑」命中时用：4 条标题不同、但讲的是同一个坑（GFM 表格竖线没转义），
# 用来验 App「按频率排序的已知坑」——出现 4 次才够叫高频。
PITFALL_ISSUES = [
    _issue("markdown-lab/md-export-kit", 311,
           "导出 Markdown 后表格少了一列（单元格里的竖线没转义）",
           "单元格内容是 `a | b` 时，导出的 GFM 表格会多出一个分隔符，整行右移。"
           "根因是拼表格时没把 `|` 转义成 `\\|`。同一个坑我们收到第 4 次报告了。",
           "closed", "2026-06-14T00:00:00Z"),
    _issue("acme-mobile/field-notes", 1204,
           "Markdown export drops a column when a cell contains a pipe",
           "Exported tables lose their last column whenever a cell has a literal pipe. "
           "Fix was escaping pipes as \\| before joining the row. Same root cause as "
           "the other export reports.",
           "closed", "2026-04-27T00:00:00Z"),
    _issue("sunlit/room-recipes", 156,
           "导出后表格整行消失：竖线要转义成 \\|",
           "用户贴进来的命令里带 `|`，导出后那一行表格直接消失，预览里看成丢数据。"
           "GFM 规定表格单元格内的竖线必须转义，我们的 exporter 漏了这一步。",
           "closed", "2026-02-03T00:00:00Z"),
    _issue("bramble/android-offline-sync", 640,
           "Table breaks after Markdown export — cells with pipe characters",
           "Any cell containing `|` shifts the row. Escaping pipes fixes the render; "
           "we also added a unit test that exports a 3-column table with pipes in every "
           "cell. Third report of this exact bug.",
           "closed", "2025-12-21T00:00:00Z"),
]

# 通用 issues：不带倾向的普通 issue，只证明「这套关键词下 github issue 检索有货」。
GENERAL_ISSUES = [
    _issue("droid-rooms/room-migration-samples", 77,
           "Room 增量查询在 3 万行之后的耗时曲线",
           "按记录存行之后，首屏只查最近 200 行，P90 从 620ms 降到 90ms；"
           "全量导出仍然要 join 5 张表，冷启动一次约 1.8s。",
           "open", "2026-08-19T00:00:00Z"),
    _issue("phrase/offline-first-notes", 355,
           "导出时 SQLite database is locked 的处理",
           "导出线程和同步线程抢同一个写连接。改成导出只读、单写者队列之后不再偶发。",
           "open", "2026-07-02T00:00:00Z"),
    _issue("searchops/github-cache-proxy", 41,
           "README 抓取遇到 404 时要不要回落到 default_branch 的 README",
           "部分仓库默认分支不是 main，硬编码 /readme 会 404。现在先读仓库元数据再取 readme。",
           "closed", "2026-05-21T00:00:00Z"),
]

CODE_ITEMS = [
    {
        "name": "CacheManager.kt",
        "path": "app/src/CacheManager.kt",
        "html_url": "https://github.com/phrase/offline-first-notes/blob/main/app/src/CacheManager.kt",
        "repository": {"full_name": "phrase/offline-first-notes"},
    },
    {
        "name": "DraftDao.kt",
        "path": "app/src/main/java/com/example/data/DraftDao.kt",
        "html_url": "https://github.com/sunlit/room-recipes/blob/main/app/src/main/java/com/example/data/DraftDao.kt",
        "repository": {"full_name": "sunlit/room-recipes"},
    },
    {
        "name": "MarkdownExporter.kt",
        "path": "app/src/main/java/com/example/export/MarkdownExporter.kt",
        "html_url": "https://github.com/markdown-lab/md-export-kit/blob/main/app/src/main/java/com/example/export/MarkdownExporter.kt",
        "repository": {"full_name": "markdown-lab/md-export-kit"},
    },
]

# README 要能支撑「常见做法」判定：本地优先 + Room 按记录存 + 导出 Markdown，
# 并且必须写出明确的取舍（只写优点的 README 不足以支撑判定，模型无从对照）。
README_TEXT = """# Field Notes（离线优先的现场记录）

所有讨论、确认单和导出元数据先落本地 SQLite，用 Room 做映射，**一条记录 = 一行**，
不再把整份会话塞进单个 JSON blob；网络只干两件事：取模型结果、补 GitHub 引用链接。
断网时可完整浏览历史，UI 标出「上次同步时间」，而不是静默失败。

## 取舍

- **本地优先 vs 多端一致**：先保证断网可用，因此多设备同步是最终一致；冲突用
  「后写者胜 + 保留 revision」，代价是两台设备同时编辑会看到短暂分叉。
- **按记录存行 vs 单文件存整份**：行存的单条读删是 O(1)，全量导出要 join 5 张表；
  我们接受这个代价，因为现场丢失数据的代价更高（见 Issue #217）。
- **导出 Markdown**：直接输出 GFM，表格里的竖线转义成 `\\|`，超长正文按 200 条分片写盘，
  避免一次性拼大字符串导致 OOM。

## 现状

Kotlin + Room + 自写 exporter，无第三方导出库；导出器有 12 个边界单测，
已知坑集中在表格转义与分片边界。
"""


# ---------------------------------------------------------------------------
# 请求流水（内存统计）
# ---------------------------------------------------------------------------
class Stats:
    """线程安全的请求流水。

    只记「路径 + 查询串 + 是否带 Authorization」——**绝不记录 Authorization 的值本身**。
    那个值是密钥，一旦进了流水就会被截图、被贴进验收文档、被日志收集，
    所以这里只留一个布尔量，源码里也不给任何打印头部的分支留口子。
    """

    def __init__(self, cap=MAX_RECORDS):
        self._lock = threading.Lock()
        self._records = []
        self._cap = cap
        self._total = 0
        self._with_auth = 0
        self._search = 0

    def add(self, method, path, query, had_auth, status):
        record = {
            "ts": datetime.now().astimezone().isoformat(timespec="seconds"),
            "method": method,
            "path": path,
            "query": query,
            "hadAuth": bool(had_auth),
            "status": int(status),
        }
        with self._lock:
            self._total += 1
            if had_auth:
                self._with_auth += 1
            if path.startswith("/search"):
                self._search += 1
            self._records.append(record)
            if len(self._records) > self._cap:
                # 只裁列表，不裁计数器：计数要能反映「真来过多少请求」。
                del self._records[: len(self._records) - self._cap]

    def snapshot(self):
        with self._lock:
            return {
                "requests": [dict(item) for item in self._records],
                "counts": {
                    "total": self._total,
                    "withAuth": self._with_auth,
                    "search": self._search,
                },
            }

    def reset(self):
        with self._lock:
            self._records.clear()
            self._total = 0
            self._with_auth = 0
            self._search = 0


STATS = Stats()


def _mode(q):
    """把 q 映射成一个开关名，没命中就是 "general"。

    为什么用关键词而不是额外参数：App 客户端是按真实 GitHub API 写的，只会拼 q；
    服务端想换行为又不能让 App 改代码，只能从 q 里偷一个标记。所以验收脚本
    在关键词后面追加「常见做法」「无人区」这类词即可，客户端无感。
    """
    low = q.lower()
    for mode, tokens in SWITCHES:
        for token in tokens:
            if token in low:
                return mode
    return "general"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # 本次请求是否已经把响应头发出去过：兜底 500 时靠它决定还要不要再写一份。
    _responded = False

    def log_message(self, fmt, *args):
        # 关掉 BaseHTTPRequestHandler 的默认逐行访问日志（会刷屏），
        # 改成下面 _log() 打的一行紧凑日志。
        pass

    # ---------------------------------------------------------------- 入口

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def _handle(self, method):
        split = urlsplit(self.path)
        path = unquote(split.path)
        query = split.query
        had_auth = bool(self.headers.get("Authorization"))
        # 观测端点自己不进流水：否则每调一次 /__stats，total 就 +1，
        # 取证时看到的数字永远对不上真实流量。
        observable = path in ("/__stats", "/__reset")
        status = 500
        self._responded = False
        try:
            if method == "GET":
                status = self._route_get(path, query, had_auth)
            else:
                status = self._route_post(path, query)
        except (BrokenPipeError, ConnectionResetError):
            # 客户端先走了（超时/取消），服务端不该因此吐一屏 traceback。
            status = 499
        except Exception as exc:  # noqa: BLE001 - 兜底，假服务不能因为一个端点写错就整台挂掉
            print("[fake-gh] handler error: %r" % (exc,), flush=True)
            status = 500
            if not self._responded:
                try:
                    self._json(500, {"message": "Internal Server Error"})
                except OSError:
                    pass
        finally:
            if not observable:
                STATS.add(method, path, query, had_auth, status)
                self._log(method, path, query, had_auth, status)

    def _log(self, method, path, query, had_auth, status):
        """一行紧凑日志：手机侧发来的请求在控制台上能直接被看见。

        注意：这里只打 q 和「是否带 Authorization」，绝不打头部原值——
        Authorization 是 App 里填的 token，属于密钥，不能进日志。
        """
        try:
            q = self._q(query)
            suffix = ("?q=" + q) if q else (("?" + query) if query else "")
            print("[fake-gh] %s %s%s -> %s auth=%s"
                  % (method, path, suffix, status, "yes" if had_auth else "no"),
                  flush=True)
        except Exception:  # 日志永远不能影响响应
            pass

    # ---------------------------------------------------------------- 路由

    def _route_get(self, path, query, had_auth):
        if path == "/__stats":
            return self._json(200, STATS.snapshot())
        if path == "/rate_limit":
            return self._json(200, self._rate_limit())
        if path in ("/search/repositories", "/search/issues", "/search/code"):
            return self._search(path.rsplit("/", 1)[1], query, had_auth)
        matched = re.match(r"^/repos/([^/]+)/([^/]+)/readme/?$", path)
        if matched:
            return self._readme(matched.group(1), matched.group(2))
        return self._json(404, {"message": "Not Found"})

    def _route_post(self, path, query):
        if path == "/__reset":
            STATS.reset()
            return self._json(200, {"ok": True})
        return self._json(404, {"message": "Not Found"})

    # ---------------------------------------------------------------- 检索

    def _search(self, kind, query, had_auth):
        q = self._q(query)

        # 代码检索在真实 GitHub 上必须鉴权，匿名一律 401。这里必须排在最前面：
        # 验的就是 App「没填 token 时」怎么降级，不能让它先看到别的错误。
        if kind == "code" and not had_auth:
            return self._json(401, {
                "message": "Requires authentication",
                "documentation_url": "https://docs.github.com/rest/search/search#search-code",
            })

        if not q.strip():
            # 真实 GitHub 对空 q 就是 422，App 里对应「关键词是空的」这条校验。
            return self._json(422, {"message": "Validation Failed"})

        mode = _mode(q)

        if mode == "ratelimit":
            # App 的「测试检索」就是打 /rate_limit 看额度，这里让检索也走 429，
            # 用来验重试与退避，以及「429 不能当成无人区」这条判定护栏。
            return self._json(
                429,
                {"message": "API rate limit exceeded",
                 "documentation_url": "https://docs.github.com/rest/overview/rate-limits-for-the-rest-api"},
                {"X-RateLimit-Remaining": "0", "Retry-After": "60"},
            )

        if mode == "slow":
            # 40s > App 的 60s 硬预算里单请求的等待额度，验「单请求超时」后
            # 整体预算怎么收尾、部分成功怎么标注。sleep 完照常返回数据。
            print("[fake-gh] slow switch: sleeping 40s on %s" % kind, flush=True)
            time.sleep(40)
            mode = "general"

        if mode == "badjson":
            # 200 但正文被截断：验 JSON 解析容错（不能崩，不能把解析失败当无人区）。
            return self._raw(200, b'{"items":')

        if kind == "code":
            items = [] if mode == "empty" else list(CODE_ITEMS)
            return self._json(200, {"total_count": len(items), "items": items})

        if kind == "repositories":
            if mode == "empty":
                items = []
            elif mode == "common":
                items = list(COMMON_REPOS)
            else:
                items = list(GENERAL_REPOS)
            return self._json(200, {"total_count": len(items), "items": items})

        # kind == "issues"
        if mode == "partial":
            # 一类源挂掉：repositories 照常、issues 500。验「部分成功」——
            # App 必须仍能给判定，同时标出「issue 源不可用」而不是直接失败。
            print("[fake-gh] partial switch: issues -> 500", flush=True)
            return self._json(500, {
                "message": "Internal Server Error",
                "documentation_url": "https://docs.github.com/rest",
            })
        if mode == "empty":
            items = []
        elif mode == "antipattern":
            items = list(ANTIPATTERN_ISSUES)
        elif mode == "pitfall":
            items = list(PITFALL_ISSUES)
        else:
            items = list(GENERAL_ISSUES)
        return self._json(200, {"total_count": len(items), "items": items})

    # ---------------------------------------------------------------- 其它端点

    def _readme(self, owner, repo):
        full_name = "%s/%s" % (owner, repo)
        body = base64.b64encode(README_TEXT.encode("utf-8")).decode("ascii")
        return self._json(200, {
            "name": "README.md",
            "path": "README.md",
            "encoding": "base64",
            "content": body,
            "html_url": "https://github.com/%s/blob/main/README.md" % full_name,
        })

    def _rate_limit(self):
        now = int(time.time())
        return {
            "resources": {
                "core": {"limit": 60, "remaining": 59, "reset": now + 3600, "used": 1},
                "search": {"limit": 10, "remaining": 9, "reset": now + 60, "used": 1},
            },
            "rate": {"limit": 60, "remaining": 59, "reset": now + 3600, "used": 1},
        }

    # ---------------------------------------------------------------- helpers

    def _q(self, query):
        """取 q 的值（已 URL 解码，中文与 `in:title,body` 里的空格都还原好）。"""
        try:
            values = parse_qs(query, keep_blank_values=True).get("q") or []
        except Exception:
            return ""
        for value in values:
            if value.strip():
                return value
        return values[0] if values else ""

    def _json(self, code, obj, extra_headers=None):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        return self._raw(code, raw, extra_headers)

    def _raw(self, code, raw, extra_headers=None):
        self.send_response(code)
        self.send_header("Content-Type", JSON_CT)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self._responded = True
        self.wfile.write(raw)
        return code


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
ENDPOINTS = [
    ("GET", "/search/repositories?q=..&sort=stars&order=desc&per_page=8", "仓库检索"),
    ("GET", "/search/issues?q=..+in:title,body+type:issue&per_page=8", "issue 检索"),
    ("GET", "/search/code?q=..&per_page=5", "代码检索（必须带 Authorization，否则 401）"),
    ("GET", "/repos/{owner}/{repo}/readme", "README 原文（base64）"),
    ("GET", "/rate_limit", "额度查询（App「测试检索」按钮）"),
    ("GET", "/__stats", "本次验收的请求流水（观测用）"),
    ("POST", "/__reset", "清空流水（观测用）"),
]


def _print_banner(host, port):
    print("[fake-gh] listening on %s:%d" % (host, port), flush=True)
    print("[fake-gh] 端点清单：", flush=True)
    for method, path, note in ENDPOINTS:
        print("[fake-gh]   %-4s %-56s %s" % (method, path, note), flush=True)
    print("[fake-gh] 验收开关（q 里带关键词即触发）：", flush=True)
    print("[fake-gh]   无人区/nobody -> 空结果  429 -> 限流  坏JSON/badjson -> 截断正文", flush=True)
    print("[fake-gh]   超时/slow -> 40s 后返回  半个/partial -> issues 500", flush=True)
    print("[fake-gh]   反模式/antipattern -> 抱怨 issue  已知坑/pitfall -> 4 条同类", flush=True)
    print("[fake-gh]   常见做法/common -> 6 个高 star 仓库  其它 -> 通用数据  空 q -> 422", flush=True)
    print("[fake-gh] 设备侧：adb reverse tcp:%d tcp:%d" % (port, port), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="假 GitHub REST API 服务（AskFirst M6 验收用）")
    parser.add_argument("--host", default=DEFAULT_HOST, help="监听地址，默认 0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="监听端口，默认 8090")
    args = parser.parse_args(argv)

    try:
        server = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        print("[fake-gh] 端口 %d 起不来（多半被占了）：%s" % (args.port, exc), flush=True)
        return 1
    server.daemon_threads = True
    _print_banner(args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[fake-gh] bye", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    try:
        # 控制台编码不一定是 UTF-8（Windows 常见 cp936 / cp1252），
        # 打日志时遇到编不出来的字符别抛 UnicodeEncodeError 把服务带崩。
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
