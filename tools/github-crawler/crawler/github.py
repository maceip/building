#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""HTTP client for the GitHub REST API, GraphQL API and the trending pages.

Design points that matter for a daemon that runs for months:

* **Conditional requests.** Every REST/GraphQL GET carries the previously
  stored ``ETag``. A ``304 Not Modified`` costs *no* rate-limit quota, so
  re-checking a repository we already know about is effectively free.
* **One client, many buckets.** The [RateLimiter] decides whether the next
  call is allowed; this module only performs it and reports what GitHub said.
* **GraphQL for the social graph.** Fetching a developer's repositories,
  starred repositories and following list costs one GraphQL request instead
  of three REST requests, which is what makes a 2-hop traversal affordable.
"""
from __future__ import annotations

import gzip
import json
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "building-crawler/1.0 (local background service)"


def _as_log_fn(logger):
    """Accept a callable, a logging.Logger, or nothing."""
    if logger is None:
        return lambda _message: None
    if callable(logger):
        return logger
    return lambda message: logger.info(message)


class GitHubError(RuntimeError):
    """A GitHub request failed in a way the caller should handle."""

    def __init__(self, message: str, status: int | None = None, retryable: bool = False):
        super().__init__(message)
        self.status = status
        self.retryable = retryable


class GitHubClient:
    """Thin, rate-limit-aware GitHub client."""

    def __init__(self, token: str, config, limiter, store, logger=None) -> None:
        self.token = token
        self.config = config
        self.limiter = limiter
        self.store = store
        self.log = _as_log_fn(logger)
        self.calls = 0
        self.cached_hits = 0

    # ------------------------------------------------------------------
    # low level
    # ------------------------------------------------------------------
    def _request(
        self,
        url: str,
        method: str = "GET",
        body: bytes | None = None,
        headers: dict | None = None,
        api: bool = True,
        resource: str = "core",
        use_cache: bool = True,
        max_attempts: int = 3,
    ):
        """Perform one HTTP request. Returns ``(status, body_text)``.

        Returns ``(304, None)`` for a not-modified response.
        """
        request_headers = {
            "User-Agent": self.config.user_agent or USER_AGENT,
            "Accept-Encoding": "gzip",
        }
        if headers:
            request_headers.update(headers)
        if api and self.token:
            request_headers["Authorization"] = f"Bearer {self.token}"
        if body is not None:
            request_headers["Content-Type"] = "application/json"

        cached_body = None
        etag = None
        if use_cache and method == "GET":
            etag, cached_body = self.store.cache_get(url)
            if etag:
                request_headers["If-None-Match"] = etag

        last_error: Exception | None = None
        for attempt in range(1, max_attempts + 1):
            self.limiter.wait_for_slot(resource=resource, web=not api)
            started = time.monotonic()
            request = urllib.request.Request(url, data=body, headers=request_headers, method=method)
            try:
                with urllib.request.urlopen(request, timeout=self.config.request_timeout) as response:
                    raw = response.read()
                    if response.headers.get("Content-Encoding") == "gzip":
                        raw = gzip.decompress(raw)
                    text = raw.decode("utf-8", errors="replace")
                    status = response.status
                    resp_headers = response.headers
            except urllib.error.HTTPError as exc:
                status = exc.code
                resp_headers = exc.headers
                raw = exc.read()
                if exc.headers.get("Content-Encoding") == "gzip":
                    try:
                        raw = gzip.decompress(raw)
                    except OSError:
                        pass
                text = raw.decode("utf-8", errors="replace")
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last_error = exc
                wait = self.limiter.note_transient_failure(attempt)
                self._log(f"transient failure on {url}: {exc} (retry in {wait:.0f}s)")
                self._record_call("api" if api else "web", method, url, 0, resource, 0, 0, 0,
                                  int((time.monotonic() - started) * 1000), f"error:{exc}")
                if attempt == max_attempts:
                    raise GitHubError(f"network error: {exc}", retryable=True) from exc
                time.sleep(wait)
                continue

            duration_ms = int((time.monotonic() - started) * 1000)
            self.limiter.observe_headers(resp_headers)

            if api:
                self.limiter.record_api_call(resource)
            else:
                self.limiter.record_web_call()

            if status in (403, 429):
                retry_after = _parse_retry_after(resp_headers)
                wait = self.limiter.note_rate_limited(resp_headers, retry_after)
                self._record_call(
                    "api" if api else "web", method, url, status, resource,
                    _int_header(resp_headers, "x-ratelimit-remaining"),
                    _int_header(resp_headers, "x-ratelimit-limit"),
                    0, duration_ms, f"rate_limited backoff={wait:.0f}s",
                )
                if attempt < max_attempts and status == 429:
                    time.sleep(min(wait, 60.0))
                    continue
                raise GitHubError(f"rate limited ({status}) on {url}", status=status)

            if status == 304:
                self.cached_hits += 1
                self._record_call(
                    "api" if api else "web", method, url, status, resource,
                    _int_header(resp_headers, "x-ratelimit-remaining"),
                    _int_header(resp_headers, "x-ratelimit-limit"),
                    1, duration_ms, "not-modified",
                )
                return 304, None

            if status == 404:
                self._record_call("api" if api else "web", method, url, status, resource,
                                  None, None, 0, duration_ms, "not-found")
                return 404, None

            if status >= 500:
                wait = self.limiter.note_transient_failure(attempt)
                self._record_call("api" if api else "web", method, url, status, resource,
                                  None, None, 0, duration_ms, f"server-error retry in {wait:.0f}s")
                if attempt < max_attempts:
                    time.sleep(wait)
                    continue
                raise GitHubError(f"server error {status} on {url}", status=status, retryable=True)

            self._record_call(
                "api" if api else "web", method, url, status, resource,
                _int_header(resp_headers, "x-ratelimit-remaining"),
                _int_header(resp_headers, "x-ratelimit-limit"),
                0, duration_ms,
            )

            if use_cache and method == "GET" and status == 200:
                self.store.cache_put(url, resp_headers.get("ETag"), text, status)

            return status, text

        raise GitHubError(f"request failed: {last_error}")

    def _record_call(self, kind, method, url, status, resource, remaining, limit_value,
                     cached, duration_ms, note=None) -> None:
        try:
            self.store.record_api_call(
                kind=kind, method=method, url=url, status=status, resource=resource,
                remaining=remaining, limit_value=limit_value, cached=bool(cached),
                duration_ms=duration_ms, note=note,
            )
        except Exception:  # accounting must never break the crawl
            pass

    def _log(self, message: str) -> None:
        if self.log:
            self.log(message)

    # ------------------------------------------------------------------
    # REST helpers
    # ------------------------------------------------------------------
    def api_get_json(self, path: str, resource: str = "core"):
        url = path if path.startswith("http") else f"{self.config.api_base}/{path.lstrip('/')}"
        status, text = self._request(url, resource=resource)
        if status == 304:
            etag, body = self.store.cache_get(url)
            return json.loads(body) if body else None
        if status != 200 or not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None

    def get_repo(self, owner: str, name: str) -> dict | None:
        """Full repository metadata."""
        data = self.api_get_json(f"repos/{owner}/{name}")
        if not data:
            return None
        return normalise_repo(data)

    def get_user(self, login: str) -> dict | None:
        data = self.api_get_json(f"users/{login}")
        if not data:
            return None
        return normalise_developer(data)

    def list_user_repos(self, login: str, per_page: int = 30) -> list[dict]:
        data = self.api_get_json(f"users/{login}/repos?sort=pushed&per_page={per_page}")
        return [normalise_repo(r) for r in data] if isinstance(data, list) else []

    def list_user_starred(self, login: str, per_page: int = 30) -> list[dict]:
        data = self.api_get_json(f"users/{login}/starred?per_page={per_page}")
        return [normalise_repo(r) for r in data] if isinstance(data, list) else []

    def list_user_following(self, login: str, per_page: int = 30) -> list[str]:
        data = self.api_get_json(f"users/{login}/following?per_page={per_page}")
        return [u.get("login", "") for u in data if isinstance(u, dict) and u.get("login")] if isinstance(data, list) else []

    def search_repositories(self, query: str, per_page: int = 30) -> list[dict]:
        encoded = urllib.parse.quote(query, safe="")
        data = self.api_get_json(
            f"search/repositories?q={encoded}&sort=stars&order=desc&per_page={per_page}",
            resource="search",
        )
        items = data.get("items") if isinstance(data, dict) else None
        return [normalise_repo(r) for r in items] if isinstance(items, list) else []

    # ------------------------------------------------------------------
    # GraphQL
    # ------------------------------------------------------------------
    def graphql(self, query: str, variables: dict | None = None) -> dict | None:
        payload = json.dumps({"query": query, "variables": variables or {}}).encode("utf-8")
        status, text = self._request(
            self.config.graphql_url,
            method="POST",
            body=payload,
            headers={"Accept": "application/vnd.github+json"},
            resource="graphql",
            use_cache=False,
        )
        if status != 200 or not text:
            return None
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return None
        if data.get("errors"):
            self._log(f"graphql error: {data['errors'][0].get('message')}")
            return None
        return data.get("data")

    def developer_bundle(self, login: str, repo_count: int = 6, star_count: int = 6,
                         follow_count: int = 6) -> dict | None:
        """One GraphQL request for a developer's repos, stars and following.

        Uses ``repositoryOwner`` with an inline fragment for ``User`` so the
        same query resolves both personal accounts (which have starred
        repositories and a follow graph) and organizations (which do not).
        """
        query = """
        query DeveloperBundle($login: String!, $repoCount: Int!, $starCount: Int!, $followCount: Int!) {
          repositoryOwner(login: $login) {
            login
            avatarUrl
            url
            repositories(first: $repoCount, orderBy: {field: PUSHED_AT, direction: DESC}) {
              nodes {
                nameWithOwner owner { login } description stargazerCount forkCount
                primaryLanguage { name } url homepageUrl isArchived isFork
                diskUsage createdAt pushedAt updatedAt
                licenseInfo { spdxId }
                defaultBranchRef { name }
                repositoryTopics(first: 10) { nodes { topic { name } } }
              }
            }
            ... on User {
              name bio company websiteUrl
              followers { totalCount }
              following(first: $followCount) { totalCount nodes { login } }
              starredRepositories(first: $starCount, orderBy: {field: STARRED_AT, direction: DESC}) {
                nodes {
                  nameWithOwner owner { login } description stargazerCount forkCount
                  primaryLanguage { name } url isArchived isFork
                }
              }
            }
            ... on Organization { name description websiteUrl }
          }
        }
        """
        data = self.graphql(
            query,
            {
                "login": login,
                "repoCount": repo_count,
                "starCount": star_count,
                "followCount": follow_count,
            },
        )
        if not data:
            return None
        owner = data.get("repositoryOwner")
        if not owner:
            return None
        return normalise_developer_bundle(owner)

    # ------------------------------------------------------------------
    # web (trending)
    # ------------------------------------------------------------------
    def fetch_trending_html(self, language: str, period: str) -> str | None:
        segment = "" if language == "all" else "/" + urllib.parse.quote(language, safe="+")
        url = f"{self.config.web_base}/trending{segment}?since={period}"
        status, text = self._request(
            url,
            api=False,
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
            },
            use_cache=False,
        )
        if status != 200 or not text:
            return None
        return text


# ----------------------------------------------------------------------
# normalisers
# ----------------------------------------------------------------------
def normalise_repo(raw: dict) -> dict:
    """Flatten a REST or GraphQL repository payload into our canonical shape."""
    full_name = raw.get("full_name") or raw.get("nameWithOwner")
    owner_block = raw.get("owner") or {}
    if isinstance(owner_block, dict):
        owner = owner_block.get("login") or ""
        owner_type = owner_block.get("type") or ""
    else:
        owner = str(owner_block)
        owner_type = ""
    name = raw.get("name") or ""
    if full_name and "/" in full_name:
        owner = full_name.split("/")[0]
        name = full_name.split("/")[1]

    language = raw.get("language")
    if not language:
        primary = raw.get("primaryLanguage") or {}
        language = primary.get("name") if isinstance(primary, dict) else None

    topics: list[str] = []
    topic_block = raw.get("topics")
    if isinstance(topic_block, list):
        topics = [t for t in topic_block if isinstance(t, str)]
    elif isinstance(topic_block, dict):
        topics = [t.get("name") for t in topic_block.get("nodes", []) if t.get("name")]

    license_block = raw.get("license") or raw.get("licenseInfo") or {}
    license_id = license_block.get("spdx_id") or license_block.get("spdxId") if isinstance(license_block, dict) else None

    branch = raw.get("default_branch")
    if not branch:
        ref = raw.get("defaultBranchRef") or {}
        branch = ref.get("name") if isinstance(ref, dict) else None

    stars = raw.get("stargazers_count")
    if stars is None:
        stars = raw.get("stargazerCount")
    forks = raw.get("forks_count")
    if forks is None:
        forks = raw.get("forkCount")

    slug = f"{owner}/{name}".lower() if owner and name else ""

    return {
        "slug": slug,
        "owner": owner,
        "name": name,
        "description": (raw.get("description") or "").strip(),
        "stars": int(stars or 0),
        "forks": int(forks or 0),
        "watchers": int(raw.get("watchers_count") or raw.get("watchers", {}).get("totalCount", 0) or 0)
        if isinstance(raw.get("watchers"), dict) else int(raw.get("watchers_count") or 0),
        "open_issues": int(raw.get("open_issues_count") or 0),
        "language": language,
        "topics": topics,
        "homepage": raw.get("homepage"),
        "html_url": raw.get("html_url") or raw.get("url"),
        "default_branch": branch or "main",
        "license": license_id,
        "archived": bool(raw.get("archived")),
        "is_fork": bool(raw.get("fork")),
        "owner_type": owner_type,
        "size_kb": raw.get("diskUsage") if raw.get("diskUsage") is not None else raw.get("size"),
        "created_at": raw.get("created_at") or raw.get("createdAt"),
        "pushed_at": raw.get("pushed_at") or raw.get("pushedAt"),
        "updated_at": raw.get("updated_at") or raw.get("updatedAt"),
        "metadata": {"raw_keys": sorted(raw.keys())},
    }


def normalise_developer(raw: dict) -> dict:
    # ``following`` is a connection in the GitHub schema and a plain integer
    # in the REST payload; ``_count`` handles both.
    following_count = raw.get("followingCount")
    if following_count is None:
        following_count = _count(raw.get("following"))
    return {
        "login": (raw.get("login") or "").lower(),
        "name": raw.get("name"),
        "bio": raw.get("bio"),
        "avatar_url": raw.get("avatar_url") or raw.get("avatarUrl"),
        "company": raw.get("company"),
        "blog": raw.get("blog") or raw.get("websiteUrl"),
        "followers": _count(raw.get("followers")),
        "following": int(following_count or 0),
        "public_repos": int(raw.get("public_repos") or raw.get("publicRepos") or 0),
        "metadata": {},
    }


def normalise_developer_bundle(user: dict) -> dict:
    """Flatten the GraphQL developer bundle into repos / stars / following."""
    repos = []
    repo_block = user.get("repositories") or {}
    for node in repo_block.get("nodes") or []:
        repos.append(normalise_repo(node))

    starred = []
    star_block = user.get("starredRepositories") or {}
    for node in star_block.get("nodes") or []:
        starred.append(normalise_repo(node))

    following = []
    follow_block = user.get("following") or {}
    for node in follow_block.get("nodes") or []:
        if node and node.get("login"):
            following.append(node["login"].lower())

    return {
        "developer": normalise_developer(user),
        "repos": repos,
        "starred": starred,
        "following": following,
    }


def _count(value) -> int:
    if isinstance(value, dict):
        return int(value.get("totalCount") or 0)
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _int_header(headers, name) -> int | None:
    if headers is None:
        return None
    try:
        raw = headers.get(name)
        return int(raw) if raw is not None else None
    except (AttributeError, TypeError, ValueError):
        return None


def _parse_retry_after(headers) -> float | None:
    if headers is None:
        return None
    try:
        raw = headers.get("retry-after")
        return float(raw) if raw else None
    except (AttributeError, TypeError, ValueError):
        return None
