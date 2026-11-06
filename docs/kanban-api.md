# Kanban plugin API — what the gateway actually serves

Every shape below is a **real reply captured from a running gateway**, not a reading of the plugin source:
`GET /api/status` → `{"version":"0.22.5","release_date":"2026.9.24","config_version":51}` (2026-11-06), dashboard
server on `127.0.0.1:9119`, board `dev` (23 tasks, 285 `task_events` rows at capture time). Values are trimmed —
titles, bodies, ids of demo rows and host names are replaced with `<…>` placeholders; **keys, nesting and types are
untouched**. Where source and the live server disagree, see §11.

The plugin is mounted at `/api/plugins/kanban`. Under a reverse proxy the whole dashboard sits on a path prefix
(this deployment uses `/hermes`), so the base URL is `https://<host>/hermes/api/plugins/kanban/…` — the prefix is a
deployment detail, not part of the contract.

Reproduce any of it with the two probes at the end (§13).

---

## 1. Auth

| Step | Call | Reply |
|---|---|---|
| Login | `POST /auth/password-login` `{"provider":"basic","username":"<u>","password":"<p>","next":""}` | `200 {"ok":true,"next":"/"}` + `Set-Cookie` |
| Who am I | `GET /api/auth/me` | `{"user_id":"<u>","email":"","display_name":"<u>","org_id":"","provider":"basic","expires_at":<unix>}` |
| WS ticket | `POST /api/auth/ws-ticket` (empty body, needs the cookie) | `{"ticket":"<43 chars>","ttl_seconds":30}` |
| Logout | `POST /auth/logout` | `302` to `/login`, cookies cleared |

- Cookies: `hermes_session_at` (access, **12 h** — `_DEFAULT_TTL_SECONDS` of the basic provider), `hermes_session_rt`
  (refresh, 30 days), `hermes_session_provider`. Over HTTPS they may be prefixed `__Host-` / `__Secure-`
  (`hermes_cli/dashboard_auth/cookies.py:27-38`). An expired access token is rotated transparently on the next
  authed request using the refresh cookie — the client does not call a refresh route.
- `provider` is required and is the provider **id** (`"basic"` for username/password), not a display name.
  Mismatch → `404 {"detail":"Unknown provider"}`; wrong password → `401 {"detail":"Invalid credentials"}`.
- The WS **cannot** use the cookie. It needs `?ticket=<…>`. The ticket is **single-use**: the second upgrade with
  the same ticket fails at the handshake with `403` (see §8). Mint one per connection, including every reconnect.
- Unauthenticated REST reply (any kanban route):
  `401 {"error":"unauthenticated","detail":"Unauthorized","reason":"no_cookie","login_url":"/login"}`

---

## 2. Endpoints

`board` is an optional query parameter everywhere; omitted → the active board. Use it explicitly: the app has its own
board switcher and must never depend on the server-side "current" pointer.

### Read

| Method | Path | Params | Reply | App needs it |
|---|---|---|---|---|
| GET | `/board` | `board`, `include_archived=false`, `tenant`, `workflow_template_id`, `current_step_key` | `{columns[], tenants[], assignees[], latest_event_id, now}` — every non-archived task, no pagination | **yes** — the only list endpoint |
| GET | `/tasks/{id}` | `board`, `run_state_type`, `run_state_name` | `{task, comments, events, attachments, links, link_tasks, child_results, runs}` | **yes** — task sheet |
| GET | `/tasks/{id}/attachments` | `board` | `{attachments:[…]}` | only if the detail is not fetched anyway |
| GET | `/attachments/{id}` | `board` | file bytes (`FileResponse`, `Content-Disposition`) | **yes** — download |
| GET | `/tasks/{id}/log` | `board`, `tail` (bytes) | `{task_id, path, exists, size_bytes, content, truncated}` — `path` is a server path, useless to a phone | **yes** — worker log |
| GET | `/runs/{id}` | `board` | single run row | optional |
| GET | `/runs/{id}/inspect` | `board` | live process: `{run_id, alive, pid, cpu_percent, memory_rss_bytes, memory_vms_bytes, num_threads, num_fds, status, create_time, cmdline}`; dead run → `{run_id, alive:false, reason:"run already ended"}` | **yes** — "is it alive" badge |
| GET | `/workers/active` | `board` | `{workers:[{run_id, task_id, task_title, task_status, task_assignee, profile, worker_pid, started_at, claim_lock, claim_expires, last_heartbeat_at, max_runtime_seconds}]}` | **yes** — run clocks |
| GET | `/boards` | `include_archived=false` | `{boards:[…], current:"dev"}` | **yes** — switcher |
| GET | `/profiles` | — | `{profiles:[{name, is_default, model, provider, description, description_auto, skill_count}]}` | **yes** — assignee picker |
| GET | `/assignees` | `board` | `{assignees:[{name, on_disk, counts:{status:n}}]}` | **yes** — assignee picker |
| GET | `/projects` | — | `{projects:[{id, slug, name, primary_path, icon, color}]}` | optional |
| GET | `/stats` | `board` | `{by_status:{…}, by_assignee:{name:{status:n}}, oldest_ready_age_seconds, now}` | optional |
| GET | `/diagnostics` | `board` | `{diagnostics:[…], count}` — empty on a healthy board | optional |
| GET | `/workflow` | — | `{columns:[{key,label,icon,drag_target}], manual:{from:[to…]}, archived:"archived"}` | **yes** — column order + legal drags |
| GET | `/config` | — | `{default_tenant, lane_by_profile, include_archived_by_default, render_markdown}` | optional |
| GET | `/orchestration` | — | `{orchestrator_profile, default_assignee, auto_decompose, auto_promote_children, resolved_*, active_profile}` | optional (settings screen) |
| GET | `/home-channels` | `task_id`, `board` | `{home_channels:[{platform, chat_id, thread_id, name, subscribed}]}` | no (chat-bridge bridge, not kanban) |
| GET | `/model-options` | — | `{…}` 11 KB of per-profile model catalog | optional |
| WS | `/events` | `board`, `since`, `ticket` | frames of `task_events` | **yes** — live board |

### Write

| Method | Path | Minimal body | Reply |
|---|---|---|---|
| POST | `/tasks` | `{"title":"…"}` (only required field) | `200 {"task":{…41 fields…}}` |
| PATCH | `/tasks/{id}` | any subset of `status, assignee, priority, title, body, result, block_reason, summary, metadata, model_override, provider_override, reasoning_effort` | `200 {"task":{…}}` |
| DELETE | `/tasks/{id}` | — | `200 {"deleted":true,"task_id":"t_…"}` (hard delete) |
| POST | `/tasks/{id}/comments` | `{"body":"…"}` (`author` optional, default `"dashboard"`) | `200 {"ok":true}` |
| POST | `/tasks/{id}/reassign` | `{"profile":"<name>"}` (`""`/null = unassign; `reclaim_first` for a running task) | `200 {"ok":true,"task_id":"t_…","assignee":"<name>"}` |
| POST | `/tasks/{id}/reclaim` | `{"reason":"…"}` | `200 {"ok":true,"task_id":"t_…"}` |
| POST | `/tasks/{id}/attachments` | multipart: `file` (required), `uploaded_by` (form) | `200 {"attachment":{…}}` |
| DELETE | `/attachments/{id}` | — | `200 {"ok":true,"id":<n>}` |
| POST | `/links` | `{"parent_id":"t_…","child_id":"t_…"}` | `200 {"ok":true,"gated":<bool>}` |
| DELETE | `/links?parent_id=…&child_id=…` | query, not body | `200 {"ok":true}` — or `{"ok":false}` when no such link (still 200) |
| POST | `/dispatch?dry_run=1&max=8` | empty | `{reclaimed, promoted, spawned:[["t_…","<profile>",""]], skipped_*, …}` |
| POST | `/runs/{id}/terminate` | `{"reason":"…"}` | `200 {"ok":true,"run_id":<n>,"task_id":"t_…"}` |
| POST | `/boards` | `{"slug":"…"}` (`name, description, icon, color, default_workdir, project_id, switch`) | `200 {"board":{…},"current":"dev"}` |
| PATCH | `/boards/{slug}` | `{name, description, icon, color, default_workdir, project_id}` (`""` clears, null leaves) | `200` board object |
| DELETE | `/boards/{slug}` | `?delete=1` for hard delete, else archive | `200 {"result":{"slug","action":"deleted"\|"archived","new_path"}}` |
| POST | `/tasks/bulk` | `{"ids":[…], "status"/"assignee"/"priority"/"archive"/"reclaim_first"/…}` | `200` per-id results |
| POST | `/estimate` | `{"title":"…","body":"…"}` | `{"ok":true,"est_tokens":55000,"complexity":"S","rationale":"…","model":"<model>"}` |
| POST | `/tasks/{id}/estimate` | `{}` | same, for a stored task (**an LLM call, 1–2 s, not persisted**) |
| POST | `/tasks/{id}/specify` | `{"author":"…"}` | LLM rewrite of the card → `{ok, task_id, reason, new_title}` |
| POST | `/tasks/{id}/decompose` | `{"author":"…"}` | LLM split into children → `{ok, task_id, reason, fanout, child_ids, new_title}` |

Attachment cap is 25 MB; over it → `413 {"detail":"attachment exceeds 25 MB limit"}`.

---

## 3. Board (`GET /board?board=dev`)

```json
{
  "columns": [
    {"name": "triage", "tasks": []},
    {"name": "todo",   "tasks": [ <card>, … ]},
    {"name": "scheduled", "tasks": []},
    {"name": "ready",  "tasks": []},
    {"name": "running","tasks": []},
    {"name": "blocked","tasks": []},
    {"name": "review", "tasks": []},
    {"name": "done",   "tasks": [ <card>, … ]}
  ],
  "tenants": [],
  "assignees": ["boss","coder","default","researcher","reviewer","tester"],
  "latest_event_id": 285,
  "now": 1793925302
}
```

Facts that matter for the client:

- `columns` is an **array of `{name, tasks}`**, always all 8 lanes in workflow order, empty ones included. It is not
  a map — do not key it by status.
- `latest_event_id` is the tail of `task_events` for that board = **the `since` cursor to open the socket with**.
- The whole board comes in one reply: 23 tasks → **105 KB**. No pagination, no server-side filter beyond `tenant`.
  A 200-task board will be ~1 MB; keep it in memory and patch it from events, don't poll it in a tight loop.
- Archived tasks are excluded unless `include_archived=1` (the column `archived` then appears). `/boards` counts them
  anyway (`"counts":{"archived":4,…}`), so a "12 tasks" badge can disagree with a 8-task board.
- `done` is sorted newest-completed-first; every queue lane is priority-DESC, created-ASC.

### A card — 44 fields

```json
{
  "id": "t_f9bdee25",
  "title": "<title>",
  "body": "<full markdown body — NOT truncated>",
  "assignee": "coder",
  "status": "todo",
  "priority": 8,
  "created_by": "user",
  "created_at": 1793923467,
  "started_at": null,
  "completed_at": null,
  "workspace_kind": "dir",
  "workspace_path": "E:/PET/<project>",
  "claim_lock": null,
  "claim_expires": null,
  "tenant": null,
  "branch_name": null,
  "project_id": null,
  "result": null,
  "idempotency_key": null,
  "consecutive_failures": 0,
  "worker_pid": null,
  "last_failure_error": null,
  "max_runtime_seconds": null,
  "last_heartbeat_at": null,
  "current_run_id": null,
  "workflow_template_id": null,
  "current_step_key": null,
  "skills": null,
  "model_override": null,
  "provider_override": null,
  "reasoning_effort": null,
  "max_retries": null,
  "goal_mode": false,
  "goal_max_turns": null,
  "session_id": null,
  "block_kind": null,
  "block_recurrences": 0,
  "completion_contract": "local-only",
  "age": {"created_age_seconds": 1857, "started_age_seconds": null, "time_to_complete_seconds": null},
  "latest_summary": null,
  "current_run_started_at": null,
  "link_counts": {"parents": 2, "children": 7},
  "comment_count": 0,
  "progress": {"done": 0, "total": 7}
}
```

All timestamps are Unix **seconds** (not millis). `age` is computed server-side at reply time — if the app keeps a
card for minutes, `created_age_seconds` goes stale; `time_to_complete_seconds` is set only for done tasks.
`progress` is `null` for a leaf card, `{"done":d,"total":n}` for a parent. `link_counts` counts edges, so a card with
2 parents and 7 children shows both. `current_run_started_at` is the **current** run's start (the run clock), while
`started_at` is the task's first-ever start — the desktop UI ticks the clock from the former.

---

## 4. Task detail (`GET /tasks/t_b96dd588?board=dev`)

Container keys, in order: `task`, `comments`, `events`, `attachments`, `links`, `link_tasks`, `child_results`, `runs`.

- `task` — the same 41 base fields as a card **minus** the board-only rollups (`link_counts`, `comment_count`,
  `progress`), **plus** `latest_summary` computed **untruncated** (cards carry a 200-char preview), and optionally
  `diagnostics` / `warnings` when the board has diagnostics for that task.
- `comments` — `[{id, task_id, author, body, created_at}]`, oldest first.
- `events` — `[{id, task_id, kind, payload, created_at, run_id}]`, oldest first. Same rows the WS streams.
- `attachments` —
  `[{"id":1,"task_id":"t_…","filename":"att.txt","content_type":"text/plain","size":17,"uploaded_by":"probe","stored_path":"C:\\Users\\…\\kanban\\boards\\dev\\attachments\\t_…\\att.txt","created_at":1793925473}]`
  — `stored_path` is a **server-side path**, meaningless to a phone; download through `/attachments/{id}`.
- `links` — `{"parents":["t_…",…],"children":["t_…",…]}`: ids only.
- `link_tasks` — `[{id, title, status}]` for **both** sides, flattened, parents first then children. Titles are only
  available here — use it to draw the dependency list without a fetch per parent.
- `child_results` — `[{id, title, status, latest_summary, result}]` for children only (the fan-out roll-up).
- `runs` — `[{id, task_id, profile, step_key, status, claim_lock, claim_expires, worker_pid, max_runtime_seconds,
  last_heartbeat_at, started_at, ended_at, outcome, summary, metadata, error}]`, oldest first. `metadata` is a
  **parsed JSON object** (not a string). `status`/`outcome` seen live: `running`, `done`/`completed`,
  `crashed`, `rate_limited`, `reclaimed`.

There is no `estimate` field on a task — see §10.

---

## 5. Boards list

```json
{"boards":[{"slug":"default","name":"Default","description":"","icon":"","color":"","default_workdir":null,
  "project_id":null,"created_at":null,"archived":false,"db_path":"C:\\…\\hermes\\kanban.db","is_current":false,
  "counts":{},"total":0,"default_workspace_kind":"scratch","project_name":null},
 {"slug":"dev", "name":"<board name>", "…":"…", "is_current":true,
  "counts":{"archived":4,"done":7,"running":4,"todo":12},"total":23,"default_workspace_kind":"scratch"}],
 "current":"dev"}
```

`counts` is keyed by status and **omits empty ones**; `total` is the sum. `db_path` is a server path — ignore it.
`is_current` mirrors the server-side pointer, which the app must not rely on (§2).

---

## 6. Workflow (drag & drop rules)

```json
{"columns":[{"key":"triage","label":"Triage","icon":"◇","drag_target":true},
            {"key":"scheduled","label":"Scheduled","icon":"⏱","drag_target":false},
            {"key":"running","label":"Running","icon":"●","drag_target":false}, …],
 "manual":{"ready":["blocked","done","review","scheduled","todo","triage"],
           "done":["ready","todo","triage"], …},
 "archived":"archived"}
```

`manual[from]` is the set of statuses a **manual** transition from `from` may target. `drag_target:false` marks
lanes the UI should not accept drops into as a *source* (scheduled/running/review are set by the engine).
A transition outside this map, or invalid for the task's current state, is refused with `409` (§9).

---

## 7. Stats / assignees / profiles / orchestration / config / diagnostics

```json
GET /stats?board=dev
{"by_status":{"done":7,"running":4,"todo":12},
 "by_assignee":{"coder":{"done":3,"running":1,"todo":8},"tester":{"done":1,"running":1,"todo":3},…},
 "oldest_ready_age_seconds":null,"now":1793925418}

GET /assignees?board=dev
{"assignees":[{"name":"coder","on_disk":true,"counts":{"done":3,"running":1,"todo":8}},
              {"name":"default","on_disk":true,"counts":{}}]}

GET /profiles
{"profiles":[{"name":"default","is_default":true,"model":"<model>","provider":"<provider>",
              "description":"","description_auto":false,"skill_count":140}, …]}

GET /orchestration
{"orchestrator_profile":"","default_assignee":"boss","auto_decompose":true,"auto_promote_children":true,
 "resolved_orchestrator_profile":"default","resolved_default_assignee":"boss","active_profile":"default"}

GET /config
{"default_tenant":"","lane_by_profile":true,"include_archived_by_default":false,"render_markdown":true}

GET /diagnostics?board=dev
{"diagnostics":[],"count":0}

GET /workers/active?board=dev
{"workers":[{"run_id":13,"task_id":"t_…","task_title":"<title>","task_status":"running",
             "task_assignee":"researcher","profile":"researcher","worker_pid":25636,
             "started_at":1793924827,"claim_lock":"<host>:<pid>","claim_expires":1793926257,
             "last_heartbeat_at":1793925357,"max_runtime_seconds":null}]}
```

`assignees` is the union of profiles on disk and assignees that hold tasks (`on_disk` says which); an empty
`counts` is a profile with no tasks on this board. Empty-string `orchestrator_profile` means "derive it" — the
`resolved_*` fields are what the server will actually use. `diagnostics` is where the server puts its own
"this card looks stuck" warnings (`stranded_in_ready`, `stale_running`, `stuck_in_blocked`, `repeated_crashes`, …);
the array is empty on a healthy board, and cards then carry no `diagnostics` key at all.

---

## 8. The event stream

```
ws(s)://<host><prefix>/api/plugins/kanban/events?board=dev&since=270&ticket=<ticket>
```

### Handshake

1. `POST /api/auth/ws-ticket` with the session cookie → `{"ticket":…,"ttl_seconds":30}`.
2. Upgrade with `?ticket=`. No ticket / garbage / already-used ticket → **`403` at the handshake, no body**.
   Not 401 — the browser cannot tell *why*, so treat any refusal after a fresh ticket as a gateway problem and retry
   with backoff.
3. `101 Switching Protocols`. The board is **pinned here**; the query string is not re-read afterwards.
4. Unknown or archived board slug → `101`, then an immediate close frame with code **`1008`** (policy violation),
   empty payload. Nothing is streamed. (Verified: bytes `88 02 03 f0`.)
5. A running board emits **no frames while idle** and no ping keepalives — silence is normal, not a dead socket.
   The server polls SQLite every 300 ms and only sends when something landed.

### Frames

Server → client, only this shape:

```json
{"events":[
   {"id":307,"task_id":"t_…","run_id":null,"kind":"commented",
    "payload":{"author":"dashboard","len":13},"created_at":1793925272},
   {"id":308,"task_id":"t_…","run_id":null,"kind":"reprioritized",
    "payload":{"priority":3},"created_at":1793925273}],
 "cursor":308}
```

- Up to **200** events per frame, ordered by `id` ascending. A backlog is drained across several frames.
- `cursor` = `id` of the last event in the frame. Persist it; it is the only reconnect state you need.
- No frame is sent when nothing happened; there is no heartbeat to time out on. Treat "connected but silent" as fine.
- Client → server messages are ignored (the server only watches for the disconnect).

### `since`

| `since` | Behaviour |
|---|---|
| absent | stream starts at the board's **current tail**; nothing is replayed |
| `0` | replays the board's **entire** history (200 at a time) |
| `n` | replays every event with `id > n` |
| `abc` / empty | treated as absent (tail) |
| `n` > tail | connects, sends nothing until the tail passes `n` |

Verified: `since=270` on a tail-319 board delivered one frame with **49 events**, `cursor=319`, kinds
`attached, attachment_removed, claimed, commented, completed, created, dependency_wait, heartbeat, owner_admitted,
promoted, spawned, worker_bound, worker_result`.

Correct opening sequence, and the one the desktop client uses: fetch `GET /board` first, then open the socket with
`since = board.latest_event_id`. Opening at `since=0` (or without a snapshot) either replays the whole history or
races: events that land between the snapshot and the socket are lost.

### Reconnect

- Mint a **new ticket** — the old one is dead (single-use, 30 s TTL).
- Reconnect with `since = last cursor seen`. Replay is cheap and bounded by 200/frame.
- If the *server* rejects the ticket (`403`), back off (the desktop does full-jitter exponential, base 500 ms, cap
  30 s, giving up after 5 consecutive refusals) — a tight retry loop hammers the gateway during a restart.
- If the close code is `1008`, the board is gone: refetch `/boards`, and do not retry that slug.
- Keep a polling fallback. The desktop polls the board every 8 s and an open task every 4 s, and treats the socket
  as an accelerator, not the source of truth. A socket that silently dies (Wi-Fi drop without a close frame) is
  invisible to the app otherwise.

### Board switch

Close the socket and open a new one with the new board's `since`. The server pins the board at the handshake, so a
single socket can never serve two boards, and cursors from two boards are not comparable (each board has its own
`task_events` table and its own id space). Do not try to reconcile two cursors mid-stream.

### On any frame

The API gives you `task_id` per event, nothing else. To stay correct: refetch the board (or apply a local
invalidate), and refetch the open task detail if it is one of the touched ids. That is exactly what the desktop does
(`onEventsFrame` → invalidate board + boards + each touched task). Event `payload` is a hint, not a patch — e.g.
`{"author":"dashboard","len":13}` for a comment does not contain the comment.

### Event kinds

Full set the code can emit, with the payload of each (payload examples are real rows from the live board):

| kind | payload | when |
|---|---|---|
| `created` | `{assignee, status, parents[], creator_task_id, tenant, workspace_kind, workspace_path, branch_name, project_id, skills, goal_mode, model_override, provider_override}` | card created |
| `edited` | `null` | title/body PATCH |
| `status` | `{"status":"todo","requested_status":"todo"}` | explicit status change |
| `reprioritized` | `{"priority":3}` | priority PATCH |
| `assigned` | `{"assignee":"tester","from":"coder"}` | reassign / PATCH assignee |
| `commented` | `{"author":"dashboard","len":1345}` | comment added (the text is **not** in the event) |
| `linked` / `unlinked` | `{"parent":"t_…","child":"t_…"}` | dependency edge |
| `dependency_wait` | `{"reason":"parent_not_done","parent":"t_…"}` | child parked behind an open parent |
| `promoted` / `promoted_manual` | `null` | auto/manual todo→ready |
| `blocked` | `{"reason":null,"kind":null,"recurrences":1,"source_status":"ready"}` or `{"reason":"initial_status","status":"blocked","actor":"user"}` | card blocked |
| `unblocked` | `null` | card released by a human |
| `claimed` | `{"lock":"<host>:<pid>","expires":<unix>,"run_id":14}` | dispatcher claimed the card |
| `reclaimed` | `{manual, reason, prev_lock, retry_status, prev_pid, host_local, termination_attempted, terminated, sigkill}` | claim released |
| `reclaim_deferred` | `{…}` | reclaim postponed |
| `spawned` | `{"pid":27384,"started_at":"\|179392493990"}` | worker process started |
| `worker_bound` | `{"pid":24180,"claim_lock":"…","started_at":"\|179392494233"}` | worker attached to the run |
| `owner_admitted` | `{db, session_id, request_id, pid, birth}` | runtime admitted the owner session |
| `worker_registered` | `{…}` | |
| `worker_result` | `{"pid":26336,"claim_lock":"…","exit_code":0,"last_output":"<tail of worker stdout>"}` | worker exited |
| `heartbeat` | `null` | **every ~minute per running task** — 95 of 281 events on this board |
| `completed` | `{"result_len":0,"summary":"<the task summary>"}` | card done |
| `review_requested` / `changes_requested` / `review_reopened` | `{…}` | review lane transitions |
| `scheduled` / `stale` / `timed_out` / `gave_up` / `crashed` / `rate_limited` / `respawn_guarded` / `skipped_nonspawnable` / `claim_rejected` / `claim_extended` / `terminal_worker_reaped` | `{pid, claimer, exit_code, retry_status, …}` | dispatcher lifecycle |
| `archived` / `deleted` | `null` | card archived / deleted (**your card may vanish**) |
| `attached` / `attachment_removed` | file metadata | attachment upload/delete |
| `decomposed` / `specified` | `{…}` | LLM decompose/specify |
| `pr_acceptance` | `{…}` | PR contract checks |
| `tip_scratch_workspace` / `workspace_cleanup_deferred_shared` / `descendant_invalidated` / `completion_blocked_empty_result` / `completion_blocked_hallucination` / `suspected_hallucinated_references` / `reconciled` / `archive_worker_termination` / `model_override_set` | `{…}` | housekeeping |

Practical guidance: the app only needs to *react* (refresh the board / the open card) for all of them; the only kinds
that change something the user sees *without* a refetch are `commented` (badge) and the lifecycle ones driving the
running spinner. Do not switch-exhaustively on `kind` — new kinds appear with gateway updates. 22 kinds were already
in this board's history at capture; the code names 46 literally plus a handful of dispatcher-side ones.

---

## 9. Errors

| Code | Case (all captured live) | Body |
|---|---|---|
| 400 | board not found on `DELETE /boards/{slug}` | `{"detail":"board 'probe-api' does not exist"}` |
| 400 | malformed board slug | `{"detail":"invalid board slug 'Bad Slug!!': must be 1-64 chars, lowercase alphanumerics / hyphens / underscores, not starting with '-' or '_'"}` |
| 400 | bad status name | `{"detail":"unknown status: nonsense"}` |
| 400 | link to self | `{"detail":"a task cannot depend on itself"}` |
| 400 | link to a task on another board | `{"detail":"unknown task(s): t_a90b5d67"}` |
| 401 | no cookie | `{"error":"unauthenticated","detail":"Unauthorized","reason":"no_cookie","login_url":"/login"}` |
| 401 | login with wrong password | `{"detail":"Invalid credentials"}` |
| 403 | WS upgrade: no / garbage / already-used ticket | empty body, status line only |
| 404 | task, run, board, unknown route | `{"detail":"task t_doesnotexist not found"}` / `{"detail":"run 999999 not found"}` / `{"detail":"board 'doesnotexist' does not exist"}` / `{"detail":"Not Found"}` |
| 409 | reclaim a non-running task | `{"detail":"cannot reclaim t_…: not in a claimable state (not running, or unknown id)"}` |
| 409 | terminate a finished run | `{"detail":"run 9 already ended"}` |
| 409 | move to `done` with open parents | `{"detail":"cannot move t_… to 'done': unsatisfied parent dependencies: t_b96dd588 (running); complete the parents first (done or archived)"}` |
| 409 | move to `ready` with open parents | `{"detail":"Cannot move to 'ready': blocked by parent(s) not done — '<title>' (t_b96dd588, status=running)"}` |
| 409 | illegal transition (`ready`→`blocked` here) | `{"detail":"status transition to 'blocked' not valid from current state"}` |
| 409 | reassign while running | `{"detail":"cannot reassign t_…: unknown id, or still running (pass reclaim_first=true to release the claim first)"}` |
| 413 | attachment over 25 MB | `{"detail":"attachment exceeds 25 MB limit"}` |
| 422 | body missing a required field | `{"detail":[{"type":"missing","loc":["body","title"],"msg":"Field required","input":{"body":"x"}}]}` |
| 429 | login rate limit | `{"detail":"Too many login attempts. Try again shortly."}` |

Notes worth coding against:

- `422` shapes are FastAPI validation arrays, `4xx` elsewhere is `{"detail":"<text>"}`. Two different shapes in one
  API — parse defensively, and never show raw `detail` of a 409 as a crash.
- `404` and `409` on `reclaim` are *the same* message shape and both mean "not now"; the app should just refresh the
  card instead of retrying.
- `DELETE /links` with a non-existent edge returns `200 {"ok":false}` — not an error.
- A missing log file is **not** an error: `GET /tasks/{id}/log` returns `200` with `{"exists":false,"content":""}`.

---

## 10. `snippet` / `latest_summary`

- **There is no `snippet` field.** Not on a card, not in a task detail, not in the OpenAPI of this plugin — and not
  in the desktop client's own `types.ts`. Whatever produces a one-line card blurb must build it client-side.
- Cards carry `body` **in full** plus `latest_summary` cut to a hard **200 characters** (`_CARD_SUMMARY_PREVIEW_CHARS`),
  which lands mid-word and mid-markdown: `"…продолжает работать до переключения ново"`. Detail carries the same
  summary **untruncated**.
- `latest_summary` is the newest non-null run summary — i.e. **agent-written markdown prose**, rendered as markdown by
  the desktop (`TaskMarkdown` → the chat renderer). Across the live board: 7 of 23 cards had one, 0 of them
  contained JSON, `{"…"}` or a raw envelope; the run rows themselves had 0 JSON-bracket summaries out of 17. So the
  "JSON garbage in summaries" worry does not reproduce on this gateway version.
- What *is* JSON is `task_runs[].metadata` (parsed object) and `last_failure_error` — both are separate fields. Do not
  concatenate them into anything the user reads.
- One known non-prose value: a reclaim/status write can put an administrative note into the summary slot, exactly
  `status changed to <status> (dashboard/direct)`. The desktop hides it with
  `/^status changed to \w+ \(dashboard\/direct\)$/` and shows it only in Runs. Copy that rule.
- Cleaning for a phone list: take `latest_summary ?? body`, collapse whitespace and normalise newlines to spaces
  (`\s+` → `" "`), strip leading list markers/`#` (a markdown body often starts with `# Title`), then truncate to the
  card's line count with `…`. Don't strip backticks/asterisks — the blurb renders markdown anyway, and stripping
  breaks code spans. If the text is empty after cleaning, fall back to `""`, not to the id.
- Card `body`: 100 KB board replies are mostly body text. If the list gets slow, render the card from `title` +
  `latest_summary` and fetch detail on open — the API has no "card without body" mode.

---

## 11. Where reality differs from the guesses

1. **No `snippet`.** A one-line card blurb is not served — build it from `latest_summary`/`body` (§10).
2. **`estimate` is not a task field.** It is a POST that calls an auxiliary LLM and returns
   `{ok, est_tokens, complexity, rationale, model}`; nothing is stored, so a re-open must re-ask (and pay 1–2 s).
   Live example: `{"ok":true,"est_tokens":320000,"complexity":"L","rationale":"Broad reconnaissance across ~15 REST
   endpoints…","model":"deepseek-v4.1-flash"}`. Error path is `200 {"ok":false,"reason":"a title is required to
   estimate"}` — **not** an HTTP error.
3. **`columns` is an array**, not a status→tasks map; `tasks` inside it is `[]` for empty lanes, so a map lookup by
   `column.name` is the app's job.
4. **A board reply is the whole board.** No pagination, no filter by assignee, no search.
5. **`since` absent ≠ `since=0`.** Missing means tail, `0` means full history — the opposite of the usual REST habit,
   and a real refetch storm if the app opens at `0`.
6. **The ticket is single-use, 30 s.** The desktop docs describe it, but the failure mode is `403`, not `401`, and it
   appears at the WS handshake, so it must not be treated as a logout.
7. **Unknown board closes with `1008`, silently, after a `101`.** A naive client sees a successful upgrade and a
   socket that dies — the close code is the only signal.
8. **`events[].payload` never carries content** — a comment event has `{"author","len":13}`, not the text. The WS is
   an invalidation channel, not a data channel.
9. **`updated_at` does not exist**; freshness is derived from `age`/`current_run_started_at`/`last_heartbeat_at`.
10. **Timestamps are seconds, not millis.** `instant = Instant.fromEpochSeconds(...)`.
11. **`DELETE /links` uses query parameters** while `POST /links` uses a body, and a missing edge is `200 ok:false`.
12. **Config `render_markdown` is advisory** — the server always returns markdown; the switch only tells the desktop
    whether to render it as such.

---

## 12. What the API does not give you

| Dashboard feature | What the API has | What the app should do |
|---|---|---|
| Board live updates of **boards** (created/renamed/archived/switched) | nothing — `/events` only streams `task_events` | refetch `/boards` when any frame arrives, and on foreground; never cache it longer than a minute |
| Agent output while it runs (streaming text) | `GET /tasks/{id}/log` (whole file, `tail` in bytes) | poll the log every few seconds while `status=running`; there is no tail-follow / cursor |
| Push notifications about finished cards | nothing in the kanban API (`/home-channels` is the chat bridge) | own local notifications, driven by `completed`/`crashed` events or a diff of the board |
| Unread / seen state on cards | nothing | keep locally (last seen event id per board) |
| Server-side search, filters, sorting | nothing | filter the fetched board in memory |
| "New since you left" | `latest_event_id` + `since` | persist the cursor per board, replay on open, show a "N changes" chip |
| Estimate stored on the task | POST only, LLM, nothing persisted | cache locally per task id, or skip |
| Attachments preview / thumbnails | raw bytes only | render by content_type; PDFs/images by the system viewer |
| Board export/import | `POST /boards/{slug}/export`, `/boards/import` — **server filesystem paths** | not usable from a phone; hide the feature |
| Diagnostics detail | `/diagnostics` returns the same rows cards carry | show as the warning chips; the string fields are server-authored English |
| Deleting a comment | not implemented (`POST` only) | hide that action |
| Editing another user's comment / author identity | `author` is free text on POST, no auth on it | send your device name; do not build identity UI |
| Multi-user, roles, per-board permissions | single user, everything allowed | no auth UI beyond login |

Two more gaps worth knowing before designing screens: `POST /tasks/bulk` exists (multi-select works server-side), and
`POST /tasks/{id}/reclaim` + `/runs/{id}/terminate` are the only way to stop a runaway worker — both 409 unless the
task is actually running, and terminate is not a "cancel", it kills the process and re-queues the card.

---

## 13. Reproducing the capture

```bash
B=http://127.0.0.1:9119            # or https://<host>/hermes
curl -sS -c jar -X POST $B/auth/password-login -H 'Content-Type: application/json' \
     -d '{"provider":"basic","username":"<u>","password":"<p>","next":""}'
curl -sS -b jar "$B/api/plugins/kanban/board?board=dev" | jq '.columns[] | {name, n: (.tasks|length)}'
curl -sS -b jar "$B/api/plugins/kanban/tasks/<task-id>?board=dev" | jq 'keys'
curl -sS -b jar -X POST "$B/api/auth/ws-ticket" | jq .
```

The raw WebSocket (stdlib only, no deps) is ~40 lines: base64 key, `GET …/events?board=dev&since=<cursor>&ticket=…`,
then unmask-free frame reads (`b0 & 0x0F` = opcode, `b1 & 0x7F` = length, 126/127 extend it). Everything in §8 was
captured that way; the probes are throwaway, not part of the app.

---

## 14. Facts a reviewer should not have to re-derive

- Version: dashboard `0.22.5` (`release_date` 2026.9.24); plugin endpoint set as listed.
- All ids are strings prefixed `t_` (tasks) / `p_` (projects); runs and attachments use integers.
- Board slugs: 1–64 chars, lowercase alphanumerics, `-`, `_`, not starting with `-`/`_`.
- `claim_lock` is `"<hostname>:<pid>"` — not a token to send anywhere.
- The board's `latest_event_id` and the WS `cursor` are the same number space per board, and **different per board**.
- `include_archived=1` adds an `archived` column; `DELETE /tasks/{id}` is a hard delete and emits `deleted`.
