#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Local SQLite index, history and graph store for the crawler.

The daemon is long-lived and append-heavy, so the database runs in WAL mode
and every write is committed immediately - a laptop that sleeps mid-cycle
must never lose the history it just collected.

Tables
------
``repos``           current known state of every repository seen
``repo_snapshots``  time-series of stars/forks/rank observations (history)
``developers``      developer profiles discovered through the follow graph
``edges``           the graph: developer->repo, developer->developer, repo->repo
``trending_runs``   one row per trending sweep
``runs``            one row per crawler cycle
``api_calls``       audit trail of every outbound request (budget proof)
``http_cache``      ETag/body cache so unchanged resources cost no quota
"""
from __future__ import annotations

import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE IF NOT EXISTS repos (
    slug             TEXT PRIMARY KEY,
    owner            TEXT NOT NULL,
    name             TEXT NOT NULL,
    description      TEXT,
    stars            INTEGER DEFAULT 0,
    forks            INTEGER DEFAULT 0,
    watchers         INTEGER DEFAULT 0,
    open_issues      INTEGER DEFAULT 0,
    language         TEXT,
    topics           TEXT,
    homepage         TEXT,
    html_url         TEXT,
    default_branch   TEXT,
    license          TEXT,
    archived         INTEGER DEFAULT 0,
    is_fork          INTEGER DEFAULT 0,
    owner_type       TEXT,
    size_kb          INTEGER,
    created_at       TEXT,
    pushed_at        TEXT,
    updated_at       TEXT,
    first_seen_at    TEXT,
    last_seen_at     TEXT,
    last_changed_at  TEXT,
    source           TEXT,
    metadata_json    TEXT
);

CREATE TABLE IF NOT EXISTS repo_snapshots (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    slug                 TEXT NOT NULL,
    observed_at          TEXT NOT NULL,
    stars                INTEGER,
    forks                INTEGER,
    open_issues          INTEGER,
    stars_in_period      INTEGER,
    period               TEXT,
    trending_rank        INTEGER,
    trending_language    TEXT,
    source               TEXT,
    FOREIGN KEY (slug) REFERENCES repos(slug) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_snap_slug_time ON repo_snapshots(slug, observed_at);
CREATE INDEX IF NOT EXISTS idx_snap_time ON repo_snapshots(observed_at);

CREATE TABLE IF NOT EXISTS developers (
    login          TEXT PRIMARY KEY,
    name           TEXT,
    bio            TEXT,
    avatar_url     TEXT,
    company        TEXT,
    blog           TEXT,
    followers      INTEGER DEFAULT 0,
    following      INTEGER DEFAULT 0,
    public_repos   INTEGER DEFAULT 0,
    first_seen_at  TEXT,
    last_seen_at   TEXT,
    metadata_json  TEXT
);

CREATE TABLE IF NOT EXISTS edges (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    src_type       TEXT NOT NULL,
    src_id         TEXT NOT NULL,
    dst_type       TEXT NOT NULL,
    dst_id         TEXT NOT NULL,
    relation       TEXT NOT NULL,
    weight         REAL DEFAULT 0,
    relevance      INTEGER,
    reason         TEXT,
    discovered_at  TEXT,
    meta_json      TEXT,
    UNIQUE (src_type, src_id, dst_type, dst_id, relation)
);
CREATE INDEX IF NOT EXISTS idx_edges_src ON edges(src_type, src_id);
CREATE INDEX IF NOT EXISTS idx_edges_dst ON edges(dst_type, dst_id);
CREATE INDEX IF NOT EXISTS idx_edges_relation ON edges(relation);

CREATE TABLE IF NOT EXISTS trending_runs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at   TEXT,
    finished_at  TEXT,
    language     TEXT,
    period       TEXT,
    repo_count   INTEGER DEFAULT 0,
    status       TEXT,
    detail       TEXT
);

CREATE TABLE IF NOT EXISTS runs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at    TEXT,
    finished_at   TEXT,
    kind          TEXT,
    status        TEXT,
    api_calls     INTEGER DEFAULT 0,
    web_calls     INTEGER DEFAULT 0,
    repos_seen    INTEGER DEFAULT 0,
    edges_added   INTEGER DEFAULT 0,
    detail        TEXT
);

CREATE TABLE IF NOT EXISTS api_calls (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           TEXT,
    kind         TEXT,
    method       TEXT,
    url          TEXT,
    status       INTEGER,
    resource     TEXT,
    remaining    INTEGER,
    limit_value  INTEGER,
    cached       INTEGER DEFAULT 0,
    duration_ms  INTEGER,
    note         TEXT
);
CREATE INDEX IF NOT EXISTS idx_api_calls_ts ON api_calls(ts);

CREATE TABLE IF NOT EXISTS http_cache (
    url        TEXT PRIMARY KEY,
    etag       TEXT,
    body       TEXT,
    fetched_at TEXT,
    status     INTEGER
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
"""


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class CrawlerStore:
    """Thin, explicit data-access layer over the crawler SQLite database."""

    def __init__(self, db_path: Path) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.db_path), timeout=30.0)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.executescript(SCHEMA)
        self._conn.commit()
        self.set_meta("schema_version", str(SCHEMA_VERSION))

    # ------------------------------------------------------------------
    def close(self) -> None:
        try:
            self._conn.commit()
            self._conn.close()
        except sqlite3.Error:
            pass

    @contextmanager
    def transaction(self):
        try:
            yield self._conn
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise

    # ------------------------------------------------------------------
    # meta
    # ------------------------------------------------------------------
    def set_meta(self, key: str, value: str) -> None:
        with self.transaction() as conn:
            conn.execute(
                "INSERT INTO meta(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )

    def get_meta(self, key: str, default: str | None = None) -> str | None:
        row = self._conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    # ------------------------------------------------------------------
    # repos
    # ------------------------------------------------------------------
    def upsert_repo(self, repo: dict[str, Any], source: str) -> tuple[bool, bool]:
        """Insert or update a repository.

        Returns ``(is_new, stars_changed)``.
        """
        slug = repo["slug"].lower()
        now = utc_now()
        existing = self._conn.execute(
            "SELECT slug, stars, forks, first_seen_at FROM repos WHERE slug=?", (slug,)
        ).fetchone()

        stars = repo.get("stars")
        forks = repo.get("forks")
        stars_changed = False
        forks_changed = False
        if existing is not None:
            prev_stars = existing["stars"]
            prev_forks = existing["forks"]
            stars_changed = prev_stars is not None and stars is not None and prev_stars != stars
            forks_changed = prev_forks is not None and forks is not None and prev_forks != forks

        topics = repo.get("topics") or []
        if isinstance(topics, str):
            topics = [t for t in topics.split(",") if t]

        values = {
            "slug": slug,
            "owner": repo.get("owner") or "",
            "name": repo.get("name") or "",
            "description": repo.get("description"),
            "stars": stars,
            "forks": forks,
            "watchers": repo.get("watchers"),
            "open_issues": repo.get("open_issues"),
            "language": repo.get("language"),
            "topics": json.dumps(topics),
            "homepage": repo.get("homepage"),
            "html_url": repo.get("html_url"),
            "default_branch": repo.get("default_branch"),
            "license": repo.get("license"),
            "archived": 1 if repo.get("archived") else 0,
            "is_fork": 1 if repo.get("is_fork") else 0,
            "owner_type": repo.get("owner_type"),
            "size_kb": repo.get("size_kb"),
            "created_at": repo.get("created_at"),
            "pushed_at": repo.get("pushed_at"),
            "updated_at": repo.get("updated_at"),
            "first_seen_at": existing["first_seen_at"] if existing else now,
            "last_seen_at": now,
            "last_changed_at": now if (stars_changed or forks_changed or existing is None) else None,
            "source": source,
            "metadata_json": json.dumps(repo.get("metadata", {}), sort_keys=True),
        }

        columns = ", ".join(values.keys())
        placeholders = ", ".join(f":{k}" for k in values)
        updates = ", ".join(
            f"{k}=excluded.{k}"
            for k in values
            if k not in ("slug", "first_seen_at")
        )
        with self.transaction() as conn:
            conn.execute(
                f"INSERT INTO repos ({columns}) VALUES ({placeholders}) "
                f"ON CONFLICT(slug) DO UPDATE SET {updates}",
                values,
            )
        return existing is None, stars_changed

    def add_snapshot(
        self,
        slug: str,
        stars: int | None = None,
        forks: int | None = None,
        open_issues: int | None = None,
        stars_in_period: int | None = None,
        period: str | None = None,
        trending_rank: int | None = None,
        trending_language: str | None = None,
        source: str = "crawl",
    ) -> None:
        with self.transaction() as conn:
            conn.execute(
                "INSERT INTO repo_snapshots (slug, observed_at, stars, forks, open_issues, "
                "stars_in_period, period, trending_rank, trending_language, source) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    slug.lower(),
                    utc_now(),
                    stars,
                    forks,
                    open_issues,
                    stars_in_period,
                    period,
                    trending_rank,
                    trending_language,
                    source,
                ),
            )

    def repo(self, slug: str) -> sqlite3.Row | None:
        return self._conn.execute("SELECT * FROM repos WHERE slug=?", (slug.lower(),)).fetchone()

    def top_repos(self, limit: int = 20) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT * FROM repos ORDER BY stars DESC LIMIT ?", (limit,)
            ).fetchall()
        )

    def trending_repos(self, limit: int = 20) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT r.*, s.trending_rank, s.period, s.stars_in_period, s.trending_language "
                "FROM repo_snapshots s JOIN repos r ON r.slug = s.slug "
                "WHERE s.trending_rank IS NOT NULL "
                "ORDER BY s.observed_at DESC, s.trending_rank ASC LIMIT ?",
                (limit,),
            ).fetchall()
        )

    def count(self, table: str) -> int:
        row = self._conn.execute(f"SELECT COUNT(*) AS n FROM {table}").fetchone()
        return int(row["n"]) if row else 0

    # ------------------------------------------------------------------
    # developers
    # ------------------------------------------------------------------
    def upsert_developer(self, dev: dict[str, Any]) -> bool:
        login = (dev.get("login") or "").lower()
        if not login:
            return False
        now = utc_now()
        existing = self._conn.execute(
            "SELECT login, first_seen_at FROM developers WHERE login=?", (login,)
        ).fetchone()
        values = {
            "login": login,
            "name": dev.get("name"),
            "bio": dev.get("bio"),
            "avatar_url": dev.get("avatar_url"),
            "company": dev.get("company"),
            "blog": dev.get("blog"),
            "followers": dev.get("followers"),
            "following": dev.get("following"),
            "public_repos": dev.get("public_repos"),
            "first_seen_at": existing["first_seen_at"] if existing else now,
            "last_seen_at": now,
            "metadata_json": json.dumps(dev.get("metadata", {}), sort_keys=True),
        }
        columns = ", ".join(values.keys())
        placeholders = ", ".join(f":{k}" for k in values)
        updates = ", ".join(f"{k}=excluded.{k}" for k in values if k not in ("login", "first_seen_at"))
        with self.transaction() as conn:
            conn.execute(
                f"INSERT INTO developers ({columns}) VALUES ({placeholders}) "
                f"ON CONFLICT(login) DO UPDATE SET {updates}",
                values,
            )
        return existing is None

    def developers(self, limit: int = 20) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT * FROM developers ORDER BY followers DESC, login LIMIT ?", (limit,)
            ).fetchall()
        )

    def unexpanded_developers(self, limit: int = 5) -> list[sqlite3.Row]:
        """Developers we know about but have never traversed.

        A developer counts as expanded once it has at least one AUTHORED or
        STARRED edge - that is the signal that its GraphQL bundle was
        pulled. The oldest untouched developer is returned first so the
        frontier is drained deterministically instead of by luck.
        """
        return list(
            self._conn.execute(
                """
                SELECT d.* FROM developers d
                LEFT JOIN (
                    SELECT src_id, COUNT(*) AS n FROM edges
                    WHERE src_type='developer' AND relation IN ('AUTHORED','STARRED')
                    GROUP BY src_id
                ) e ON e.src_id = d.login
                LEFT JOIN (
                    SELECT src_id, COUNT(*) AS n FROM edges
                    WHERE src_type='developer' AND relation='EXPANSION_ATTEMPTED'
                    GROUP BY src_id
                ) a ON a.src_id = d.login
                WHERE COALESCE(e.n, 0) = 0 AND COALESCE(a.n, 0) = 0
                ORDER BY d.last_seen_at ASC, d.login ASC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        )

    def mark_developer_expanded(self, login: str) -> None:
        """Record that a developer was attempted, even if the attempt failed."""
        with self.transaction() as conn:
            conn.execute(
                "UPDATE developers SET last_seen_at=? WHERE login=?",
                (utc_now(), login.lower()),
            )
            conn.execute(
                "INSERT OR IGNORE INTO edges "
                "(src_type, src_id, dst_type, dst_id, relation, weight, relevance, reason, "
                " discovered_at, meta_json) VALUES ('developer',?, 'developer', ?, 'EXPANSION_ATTEMPTED', 0, 0, "
                "'Traversal attempted', ?, '{}')",
                (login.lower(), login.lower(), utc_now()),
            )

    def repos_for_linking(self, limit: int = 400) -> list[sqlite3.Row]:
        """Active, non-fork repositories considered for similarity edges."""
        return list(
            self._conn.execute(
                "SELECT slug, owner, name, description, language, topics, stars FROM repos "
                "WHERE archived=0 AND is_fork=0 ORDER BY stars DESC LIMIT ?",
                (limit,),
            ).fetchall()
        )

    # ------------------------------------------------------------------
    # edges (the graph)
    # ------------------------------------------------------------------
    def add_edge(
        self,
        src_type: str,
        src_id: str,
        dst_type: str,
        dst_id: str,
        relation: str,
        weight: float = 0.0,
        relevance: int | None = None,
        reason: str | None = None,
        meta: dict | None = None,
    ) -> bool:
        """Insert an edge if it is new. Returns True when newly created."""
        src_id = src_id.lower()
        dst_id = dst_id.lower()
        with self.transaction() as conn:
            cur = conn.execute(
                "INSERT OR IGNORE INTO edges "
                "(src_type, src_id, dst_type, dst_id, relation, weight, relevance, reason, "
                " discovered_at, meta_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    src_type,
                    src_id,
                    dst_type,
                    dst_id,
                    relation,
                    weight,
                    relevance,
                    reason,
                    utc_now(),
                    json.dumps(meta or {}, sort_keys=True),
                ),
            )
            return cur.rowcount > 0

    def edge_count(self) -> int:
        return self.count("edges")

    def neighbours(self, node_type: str, node_id: str) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT * FROM edges WHERE (src_type=? AND src_id=?) OR (dst_type=? AND dst_id=?)",
                (node_type, node_id.lower(), node_type, node_id.lower()),
            ).fetchall()
        )

    def edge_breakdown(self) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT relation, COUNT(*) AS n FROM edges GROUP BY relation ORDER BY n DESC"
            ).fetchall()
        )

    def graph_stats(self) -> dict:
        repos = self.count("repos")
        devs = self.count("developers")
        edges = self.count("edges")
        snapshots = self.count("repo_snapshots")
        return {
            "repos": repos,
            "developers": devs,
            "edges": edges,
            "snapshots": snapshots,
            "avg_degree": round(edges / repos, 2) if repos else 0.0,
        }

    # ------------------------------------------------------------------
    # runs / accounting
    # ------------------------------------------------------------------
    def start_run(self, kind: str) -> int:
        with self.transaction() as conn:
            cur = conn.execute(
                "INSERT INTO runs (started_at, kind, status) VALUES (?,?,?)",
                (utc_now(), kind, "running"),
            )
            return int(cur.lastrowid)

    def finish_run(self, run_id: int, status: str, **fields) -> None:
        sets = ["finished_at=?", "status=?"]
        values: list[Any] = [utc_now(), status]
        for key in ("api_calls", "web_calls", "repos_seen", "edges_added", "detail"):
            if key in fields:
                sets.append(f"{key}=?")
                values.append(fields[key])
        values.append(run_id)
        with self.transaction() as conn:
            conn.execute(f"UPDATE runs SET {', '.join(sets)} WHERE id=?", values)

    def record_trending_run(
        self,
        language: str,
        period: str,
        repo_count: int,
        status: str,
        detail: str | None = None,
    ) -> int:
        with self.transaction() as conn:
            cur = conn.execute(
                "INSERT INTO trending_runs (started_at, finished_at, language, period, "
                "repo_count, status, detail) VALUES (?,?,?,?,?,?,?)",
                (utc_now(), utc_now(), language, period, repo_count, status, detail),
            )
            return int(cur.lastrowid)

    def record_api_call(
        self,
        kind: str,
        method: str,
        url: str,
        status: int,
        resource: str | None = None,
        remaining: int | None = None,
        limit_value: int | None = None,
        cached: bool = False,
        duration_ms: int | None = None,
        note: str | None = None,
    ) -> None:
        with self.transaction() as conn:
            conn.execute(
                "INSERT INTO api_calls (ts, kind, method, url, status, resource, remaining, "
                "limit_value, cached, duration_ms, note) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    utc_now(),
                    kind,
                    method,
                    url,
                    status,
                    resource,
                    remaining,
                    limit_value,
                    1 if cached else 0,
                    duration_ms,
                    note,
                ),
            )

    def api_call_count(self, since_minutes: int | None = None) -> int:
        if since_minutes is None:
            row = self._conn.execute("SELECT COUNT(*) AS n FROM api_calls").fetchone()
        else:
            cutoff = time.time() - since_minutes * 60
            row = self._conn.execute(
                "SELECT COUNT(*) AS n FROM api_calls WHERE ts >= ?",
                (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(cutoff)),),
            ).fetchone()
        return int(row["n"]) if row else 0

    def snapshots_for(self, slug: str, limit: int = 100) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT observed_at, stars, forks, stars_in_period, period, trending_rank, source "
                "FROM repo_snapshots WHERE slug=? ORDER BY observed_at ASC LIMIT ?",
                (slug.lower(), limit),
            ).fetchall()
        )

    def export_repos(self) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT slug, owner, name, description, stars, forks, language, topics, "
                "html_url, first_seen_at, last_seen_at FROM repos ORDER BY stars DESC"
            ).fetchall()
        )

    def export_edges(self) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT src_type, src_id, dst_type, dst_id, relation, relevance, reason, "
                "discovered_at FROM edges"
            ).fetchall()
        )

    def last_known_quota(self) -> dict:
        """The most recent GitHub-reported quota per resource.

        Read from the audit trail so `status` reports what GitHub actually
        said, not a freshly constructed (and therefore empty) limiter.
        """
        out: dict[str, dict] = {}
        rows = self._conn.execute(
            "SELECT resource, remaining, limit_value, ts FROM api_calls "
            "WHERE resource IS NOT NULL AND remaining IS NOT NULL ORDER BY id DESC"
        ).fetchall()
        for row in rows:
            resource = row["resource"]
            if resource in out:
                continue
            out[resource] = {
                "remaining": row["remaining"],
                "limit": row["limit_value"],
                "observed_at": row["ts"],
            }
        return out

    def recent_runs(self, limit: int = 5) -> list[sqlite3.Row]:
        return list(
            self._conn.execute("SELECT * FROM runs ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        )

    def recent_api_calls(self, limit: int = 20) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT * FROM api_calls ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        )

    # ------------------------------------------------------------------
    # http cache (ETag)
    # ------------------------------------------------------------------
    def cache_get(self, url: str) -> tuple[str | None, str | None]:
        row = self._conn.execute(
            "SELECT etag, body FROM http_cache WHERE url=?", (url,)
        ).fetchone()
        if not row:
            return None, None
        return row["etag"], row["body"]

    def cache_put(self, url: str, etag: str | None, body: str | None, status: int) -> None:
        with self.transaction() as conn:
            conn.execute(
                "INSERT INTO http_cache (url, etag, body, fetched_at, status) VALUES (?,?,?,?,?) "
                "ON CONFLICT(url) DO UPDATE SET etag=excluded.etag, body=excluded.body, "
                "fetched_at=excluded.fetched_at, status=excluded.status",
                (url, etag, body, utc_now(), status),
            )
