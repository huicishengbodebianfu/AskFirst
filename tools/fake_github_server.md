# 假 GitHub 服务（AskFirst M6 真机验收用）

`fake_github_server.py` 是 `api.github.com` 的本机替身：路径、字段名、状态码与真实 REST API
一致，App 只需要把 base_url 换一行，代码不动。只用 Python 3 标准库（`ThreadingHTTPServer`），
Windows 上 `python` 直接跑，不装任何依赖。

```powershell
cd F:\安卓\AskFirstApp\tools
python fake_github_server.py                  # 0.0.0.0:8090
python fake_github_server.py --port 8091      # 换端口（--host 127.0.0.1 可只给本机）
```

## 端口与设备侧接法

| 项 | 值 |
| --- | --- |
| 监听 | `0.0.0.0:8090`（默认，可用 `--host` / `--port` 改） |
| 为什么不是 127.0.0.1 | 模拟器走 `10.0.2.2`、手机直连 Wi-Fi 打到本机 IP 时流量落在网卡上 |
| 物理机接线 | `adb reverse tcp:8090 tcp:8090` |
| App 侧 base_url | `http://127.0.0.1:8090`（debug 构建已放行回环明文） |
| App 侧 token | 随便填个非空值即可；`/search/code` 必须带 `Authorization`，真实 GitHub 匿名也是 401 |

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/search/repositories?q=..&sort=stars&order=desc&per_page=8` | 仓库检索 `{total_count, items[]}` |
| GET | `/search/issues?q=..+in:title,body+type:issue&per_page=8` | issue 检索，字段含 `repository_url` |
| GET | `/search/code?q=..&per_page=5` | 代码检索；**无 `Authorization` → 401** `Requires authentication` |
| GET | `/repos/{owner}/{repo}/readme` | README 原文，`content` 是 base64（正文 506 字，中英混合） |
| GET | `/rate_limit` | 真实格式 `resources.core` / `resources.search`（App「测试检索」按钮打它） |
| GET | `/__stats` | **观测**：请求流水 + `{total, withAuth, search}` 计数 |
| POST | `/__reset` | **观测**：清空流水，`{"ok":true}` |

未知路径一律 `404 {"message":"Not Found"}`；所有响应都是
`Content-Type: application/json; charset=utf-8`，汉字 `ensure_ascii=False`。

## 验收开关（把关键词塞进 `q` 即触发，客户端无感）

服务端对**解码后的** `q` 做子串匹配，大小写不敏感；顺序即优先级（同时命中时取靠前的）。

| q 里含 | repositories | issues | 验的是 |
| --- | --- | --- | --- |
| `无人区` / `nobody` | `total_count:0, items:[]` | `total_count:0, items:[]` | 判定「无人区」；必须同时附「已覆盖关键词」 |
| `反模式` / `antipattern` | 正常 6 条 | 3 条抱怨 issue，正文带原话 `caused corruption`、`moved to per-record rows` | 判定「反模式」+ App 能引用原话 |
| `已知坑` / `pitfall` | 正常 6 条 | 4 条**标题互不相同、同一个坑**（GFM 表格竖线未转义） | 「按频率排序的已知坑」 |
| `常见做法` / `common` | 6 条高 star（12.8k~46.2k，6 个独立 owner） | 3 条普通 issue | 「≥3 个独立项目一致」 |
| `半个` / `partial` | 正常 6 条 | **HTTP 500** | 部分成功：一类源挂了仍要给判定并标注 |
| `超时` / `slow` | sleep 40s 后正常返回 | 同左 | 60s 硬预算 / 单请求超时后的收尾 |
| `429` | **HTTP 429** + `X-RateLimit-Remaining: 0` + `Retry-After: 60` | 同左 | 限流重试与退避；429 **不能**被当成无人区 |
| `坏JSON` / `badjson` | 200 但正文是截断的 `{"items":` | 同左 | JSON 解析容错（不崩、不误判） |
| 其它关键词 | 通用 6 条 | 通用 3 条 | 任何时候都不会「空手」判定 |
| 空 `q` | **422** `{"message":"Validation Failed"}` | 同左 | 关键词为空时的校验 |

几个刻意的设计取舍：

- **忽略 `per_page`**：数据条数写死，验收结果才可复现（`常见做法` 永远 6 条）。
- **时间戳写死**：截图取证时任何两次跑出来都一样。
- **`/__stats` 不进自己的流水**：否则每查一次统计 `total` 就 +1，数字永远对不上真实流量。
- **`Authorization` 的值绝不落盘**：流水和日志里只有 `hadAuth: true/false`，token 是密钥。
- 流水只留最近 500 条（计数器不裁），并发下用 `threading.Lock` 保护。

## 自检

`curl` 在 Windows PowerShell 里可能是 `Invoke-WebRequest` 的别名，**请用 `curl.exe`**。
中文查询串建议直接 percent-encoded（下表可复制），避免控制台编码踩坑。

```powershell
# 0) 清零流水，方便一次验收只留一次证据
curl.exe -s -X POST http://127.0.0.1:8090/__reset

# 1) 常见做法 → 200 且 items >= 3
curl.exe -s "http://127.0.0.1:8090/search/repositories?q=%E5%B8%B8%E8%A7%81%E5%81%9A%E6%B3%95&sort=stars&order=desc&per_page=8"
# 2) 无人区 → 200 且 total_count = 0
curl.exe -s "http://127.0.0.1:8090/search/repositories?q=%E6%97%A0%E4%BA%BA%E5%8C%BA&per_page=8"
# 3) 反模式 → 200 且 items >= 2，正文含抱怨原话
curl.exe -s "http://127.0.0.1:8090/search/issues?q=%E5%8F%8D%E6%A8%A1%E5%BC%8F%20in%3Atitle%2Cbody%20type%3Aissue&per_page=8"
# 4) 代码检索：无 Authorization → 401；带 token → 200
curl.exe -s -o NUL -w "%{http_code}`n" "http://127.0.0.1:8090/search/code?q=x&per_page=5"
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer test" "http://127.0.0.1:8090/search/code?q=x&per_page=5"
# 5) 429 → 429 且带 X-RateLimit-Remaining
curl.exe -s -D - "http://127.0.0.1:8090/search/repositories?q=429"
# 6) README → 200，content 能 base64 解出正文
curl.exe -s "http://127.0.0.1:8090/repos/a/b/readme"
# 7) 观测：能看到前面几次请求（含 hadAuth）
curl.exe -s "http://127.0.0.1:8090/__stats"
```

PowerShell 里不想记 percent-encoded，就用 `[uri]::EscapeDataString`：

```powershell
$q = [uri]::EscapeDataString('常见做法')
Invoke-RestMethod "http://127.0.0.1:8090/search/repositories?q=$q&per_page=8" |
    Select-Object total_count, @{n='items'; e={$_.items.Count}}
# README 解码
$r = Invoke-RestMethod 'http://127.0.0.1:8090/repos/a/b/readme'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($r.content))
```

### 实测结果（2026-11-24，Python 3.14.5 / Windows，端口 8090）

```
[1] /search/repositories?q=常见做法  -> HTTP 200 items=6 total=6 owners=6
    stars: 12840,21457,8320,46210,15936,6715  first=acme-mobile/field-notes lang=Kotlin branch=main
[2] /search/repositories?q=无人区     -> HTTP 200 total_count=0 items=0
     /search/issues?q=无人区       -> HTTP 200 total_count=0 items=0
[3] /search/issues?q=反模式...       -> HTTP 200 items=3
    bodies contain 'caused corruption'=True  'moved to per-record rows'=True
    issue urls: /kizzy/cache-dojo/issues/217 /phrase/offline-first-notes/issues/402 /droid-rooms/room-migration-samples/issues/88
[4a] /search/code 无 Authorization   -> HTTP 401 {"message": "Requires authentication", ...}
[4b] /search/code 带 Bearer test     -> HTTP 200 items=3 first=CacheManager.kt repo=phrase/offline-first-notes
[5] /search/repositories?q=429        -> HTTP 429  X-RateLimit-Remaining: 0 | Retry-After: 60
[6] /repos/a/b/readme                 -> HTTP 200
    encoding=base64 chars=506 has_local_first=True has_Room=True has_export_md=True has_tradeoff=True
[7] /__stats -> HTTP 200
    counts = {'total': 16, 'withAuth': 1, 'search': 13}
    05 GET  /search/code                                 auth=True  200
    06 GET  /search/repositories                          auth=False 429
    ...
    （7 条全过；流水里只有 hadAuth 布尔量，全文搜不到 "Bearer test" / "Authorization"）
```

附带也验过：`半个/partial`（issues 500 + repos 200 共 6 条）、`已知坑/pitfall`（4 条，4 个不同标题、
4 个不同仓库）、`坏JSON`（200 + `{"items":`，9 字节，Content-Type 仍是 JSON）、
`/rate_limit`（`core 60/59`、`search 10/9`）、未知路径 404、空 q 422、
`超时/slow`（实测 40.003s 后返回 6 条）、8 路并发后 `total:8 withAuth:2 search:6` 计数无丢失。
服务端 stderr 全程为空（无 traceback）。
