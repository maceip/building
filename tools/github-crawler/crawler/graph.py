#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The developer / repository graph crawler.

Ported from the Kotlin implementation in
``app/ide-core/src/main/kotlin/dev/ide/core/discovery/``:

* ``GitHubDiscoveryClient.crawlRelatedAndAuthorNetwork`` - the 2-hop
  traversal (author repos, author stars, followed peers, their repos and
  stars, second-degree peers).
* ``DeveloperGraphEngine.rankAndFilterRelatedToApp`` - the relevance
  scoring (connection weight + language bonus + keyword bonus + star bonus).
* ``DeveloperGraphEngine.filterAndRankPeerProjects`` - the fork / minimum
  star filtering.

Graph model
-----------
Nodes are ``developer`` (login) and ``repo`` (owner/name). Edges are:

``developer -AUTHORED-> repo``    wrote the repository
``developer -STARRED-> repo``     starred the repository
``developer -FOLLOWS-> developer`` follows
``repo -RELATED-> repo``          architecturally / topically similar

The Kotlin ``ConnectionType`` label is preserved in the edge ``reason`` so a
row can always be traced back to the original crawler.
"""
from __future__ import annotations

import re
from typing import Any

# Kotlin ConnectionType -> our edge relation, with the original label kept
# as the human readable reason.
RELATION_AUTHORED = "AUTHORED"
RELATION_STARRED = "STARRED"
RELATION_FOLLOWS = "FOLLOWS"
RELATION_RELATED = "RELATED"

# Base connection weights, identical to DeveloperGraphEngine.
CONNECTION_WEIGHTS = {
    "SAME_AUTHOR": 36,
    "AUTHOR_STARRED": 32,
    "FOLLOWED_PEER": 28,
    "PEER_STARRED": 25,
    "SECOND_DEGREE_PEER": 22,
    "SECOND_DEGREE_STARRED": 20,
    "RELATED_ARCHITECTURE": 30,
    "SHARED_ORGANIZATION": 28,
}

STOP_WORDS = {
    "the", "and", "for", "with", "that", "this", "from", "your", "into", "using",
    "app", "application", "project", "repo", "repository", "library", "tool",
    "open", "source",
}

_TOKEN_SPLIT = re.compile(r"[^a-z0-9+#-]+")
_NAME_SPLIT = re.compile(r"[-_.]")


def _tokens(*values: str) -> set[str]:
    out: set[str] = set()
    for value in values:
        if not value:
            continue
        out.update(t for t in _TOKEN_SPLIT.split(value.lower()) if len(t) >= 3 and t not in STOP_WORDS)
    return out


def _name_tokens(name: str) -> set[str]:
    if not name:
        return set()
    return {t for t in _NAME_SPLIT.split(name.lower()) if len(t) >= 3 and t not in STOP_WORDS}


def relevance_score(
    connection: str,
    target_language: str,
    peer_language: str,
    target_keywords: set[str],
    peer_description: str,
    peer_name: str,
    peer_stars: int,
) -> tuple[int, str]:
    """Port of ``DeveloperGraphEngine.rankAndFilterRelatedToApp`` scoring."""
    peer_keywords = _tokens(peer_description) | _name_tokens(peer_name)
    shared = target_keywords & peer_keywords
    same_lang = bool(target_language) and (peer_language or "").lower() == target_language.lower()

    base = CONNECTION_WEIGHTS.get(connection, 20)
    lang_bonus = 24 if same_lang else 0
    keyword_bonus = min(len(shared) * 12, 36)
    if peer_stars >= 10_000:
        star_bonus = 12
    elif peer_stars >= 1_000:
        star_bonus = 9
    elif peer_stars >= 100:
        star_bonus = 6
    elif peer_stars >= 15:
        star_bonus = 3
    else:
        star_bonus = 0

    total = max(10, min(99, base + lang_bonus + keyword_bonus + star_bonus))

    parts: list[str] = []
    if same_lang:
        parts.append(f"Shared {peer_language} stack")
    if shared:
        parts.append("Matches " + ", ".join(sorted(shared)[:3]))
    if not parts:
        parts.append(connection.replace("_", " ").title())
    return total, " • ".join(parts)


class GraphCrawler:
    """Expands the local developer/repository graph using the GitHub API."""

    def __init__(self, client, store, config, logger=None) -> None:
        self.client = client
        self.store = store
        self.config = config
        if logger is None:
            self.log = lambda _m: None
        elif callable(logger):
            self.log = logger
        else:
            self.log = lambda m: logger.info(m)

    # ------------------------------------------------------------------
    def expand_developer(self, login: str, depth: int = 2) -> dict[str, Any]:
        """Expand one developer and (optionally) their follow graph.

        One GraphQL request yields the developer's repositories, starred
        repositories and following list, which is three times cheaper than
        the equivalent REST calls.
        """
        login = login.lower()
        summary = {"login": login, "repos": 0, "starred": 0, "following": 0, "edges": 0}

        bundle = self.client.developer_bundle(
            login,
            repo_count=self.config.peer_fetch_count,
            star_count=self.config.peer_fetch_count,
            follow_count=self.config.peer_fetch_count,
        )
        if not bundle:
            self.log(f"  developer {login}: no data")
            return summary

        developer = bundle["developer"]
        developer["login"] = login
        self.store.upsert_developer(developer)

        for repo in bundle["repos"]:
            if not repo["slug"]:
                continue
            self._record_repo(repo, source="graph")
            if self.store.add_edge(
                "developer", login, "repo", repo["slug"], RELATION_AUTHORED,
                relevance=CONNECTION_WEIGHTS["SAME_AUTHOR"],
                reason="Other Work by Author",
            ):
                summary["edges"] += 1
            summary["repos"] += 1

        for repo in bundle["starred"]:
            if not repo["slug"]:
                continue
            self._record_repo(repo, source="graph")
            if self.store.add_edge(
                "developer", login, "repo", repo["slug"], RELATION_STARRED,
                relevance=CONNECTION_WEIGHTS["AUTHOR_STARRED"],
                reason="Starred by Author",
            ):
                summary["edges"] += 1
            summary["starred"] += 1

        for followed in bundle["following"]:
            if self.store.add_edge(
                "developer", login, "developer", followed, RELATION_FOLLOWS,
                relevance=CONNECTION_WEIGHTS["FOLLOWED_PEER"],
                reason="Built by Author's Trusted Peer",
            ):
                summary["edges"] += 1
            # Seed the followed account as a developer node so it becomes part
            # of the frontier and gets expanded on a later cycle. Without this
            # the traversal never reaches a second hop.
            self.store.upsert_developer({"login": followed})
            summary["following"] += 1

        # Second hop: expand a small number of the followed peers.
        if depth > 1:
            peers = bundle["following"][: max(1, self.config.peer_fetch_count // 2)]
            for peer in peers:
                peer_bundle = self.client.developer_bundle(
                    peer,
                    repo_count=max(2, self.config.peer_fetch_count // 2),
                    star_count=max(2, self.config.peer_fetch_count // 2),
                    follow_count=2,
                )
                if not peer_bundle:
                    continue
                peer_dev = peer_bundle["developer"]
                peer_dev["login"] = peer
                self.store.upsert_developer(peer_dev)
                for repo in peer_bundle["repos"]:
                    if not repo["slug"]:
                        continue
                    self._record_repo(repo, source="graph")
                    if self.store.add_edge(
                        "developer", peer, "repo", repo["slug"], RELATION_AUTHORED,
                        relevance=CONNECTION_WEIGHTS["FOLLOWED_PEER"],
                        reason="Built by Author's Trusted Peer",
                    ):
                        summary["edges"] += 1
                for repo in peer_bundle["starred"]:
                    if not repo["slug"]:
                        continue
                    self._record_repo(repo, source="graph")
                    if self.store.add_edge(
                        "developer", peer, "repo", repo["slug"], RELATION_STARRED,
                        relevance=CONNECTION_WEIGHTS["PEER_STARRED"],
                        reason="Starred by Followed Peer",
                    ):
                        summary["edges"] += 1

        self.log(
            f"  developer {login}: +{summary['repos']} repos, +{summary['starred']} stars, "
            f"+{summary['following']} follows, +{summary['edges']} edges"
        )
        return summary

    # ------------------------------------------------------------------
    def expand_trending_repo(self, record: dict, depth: int = 2) -> dict:
        """Enrich a trending entry with REST metadata and link its author."""
        owner, name = record["owner"], record["name"]
        full = self.client.get_repo(owner, name)
        if full:
            repo = full
        else:
            repo = {
                "slug": f"{owner}/{name}".lower(),
                "owner": owner,
                "name": name,
                "description": record.get("description", ""),
                "stars": record.get("stars", 0),
                "forks": record.get("forks", 0),
                "language": record.get("language"),
                "topics": [],
                "html_url": record.get("html_url"),
                "default_branch": record.get("default_branch", "main"),
                "archived": False,
                "is_fork": False,
            }
        repo["stars_in_period"] = record.get("stars_in_period")
        repo["trending_rank"] = record.get("trending_rank")
        repo["trending_period"] = record.get("trending_period")
        repo["trending_language"] = record.get("trending_language")

        self._record_repo(repo, source="trending")
        self.store.add_snapshot(
            slug=repo["slug"],
            stars=repo.get("stars"),
            forks=repo.get("forks"),
            open_issues=repo.get("open_issues"),
            stars_in_period=record.get("stars_in_period"),
            period=record.get("trending_period"),
            trending_rank=record.get("trending_rank"),
            trending_language=record.get("trending_language"),
            source="trending",
        )

        if owner:
            self.store.upsert_developer({"login": owner.lower()})
            self.store.add_edge(
                "developer", owner.lower(), "repo", repo["slug"], RELATION_AUTHORED,
                relevance=CONNECTION_WEIGHTS["SAME_AUTHOR"], reason="Other Work by Author",
            )
            if depth > 0:
                self.expand_developer(owner.lower(), depth=depth)
        return repo

    # ------------------------------------------------------------------
    def _record_repo(self, repo: dict, source: str) -> None:
        is_new, stars_changed = self.store.upsert_repo(repo, source=source)
        if stars_changed or is_new:
            self.store.add_snapshot(
                slug=repo["slug"],
                stars=repo.get("stars"),
                forks=repo.get("forks"),
                open_issues=repo.get("open_issues"),
                source=source,
            )

    # ------------------------------------------------------------------
    def compute_related_edges(self, limit: int = 400) -> int:
        """Derive repo->repo similarity edges from the local index.

        Purely offline - no API cost - and it is what gives the graph its
        "related architecture" layer.
        """
        rows = list(self.store.repos_for_linking(limit=400))
        added = 0
        prepared = []
        for row in rows:
            topics = set()
            if row["topics"]:
                try:
                    import json

                    topics = set(json.loads(row["topics"]))
                except (ValueError, TypeError):
                    topics = set()
            prepared.append({
                "row": row,
                "topics": topics,
                "keywords": _tokens(row["description"] or "") | _name_tokens(row["name"] or ""),
            })

        for i, left in enumerate(prepared):
            for right in prepared[i + 1:]:
                lrow, rrow = left["row"], right["row"]
                if lrow["owner"] == rrow["owner"]:
                    continue
                shared_topics = left["topics"] & right["topics"]
                shared_keywords = left["keywords"] & right["keywords"]
                if not shared_topics and len(shared_keywords) < 2:
                    continue
                same_lang = bool(lrow["language"]) and lrow["language"] == rrow["language"]
                if not shared_topics and not same_lang:
                    continue

                connection = "RELATED_ARCHITECTURE"
                score, reason = relevance_score(
                    connection,
                    lrow["language"] or "",
                    rrow["language"] or "",
                    left["keywords"],
                    rrow["description"] or "",
                    rrow["name"] or "",
                    rrow["stars"] or 0,
                )
                if shared_topics:
                    reason = "Shares topic " + ", ".join(sorted(shared_topics)[:2])
                if self.store.add_edge(
                    "repo", lrow["slug"], "repo", rrow["slug"], RELATION_RELATED,
                    weight=float(len(shared_topics) + len(shared_keywords) / 2),
                    relevance=score,
                    reason=reason,
                ):
                    added += 1
                if added >= limit:
                    return added
        return added
