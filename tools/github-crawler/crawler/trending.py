#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""GitHub Trending scraper.

Ported from the Kotlin implementation in
``app/ide-core/src/main/kotlin/dev/ide/core/discovery/GitHubDiscoveryClient.kt``
(``parseTrendingHtml`` / ``fetchLiveTrendingRepositories``). The parsing
rules are kept byte-for-byte equivalent so the local index matches what the
in-app explorer sees.

GitHub's trending board is plain HTML and costs **no** API quota, which is
why it is the cheapest way to keep the local index fresh.
"""
from __future__ import annotations

import re

SUPPORTED_PERIODS = ("daily", "weekly", "monthly")

# Kept equivalent to the Kotlin regular expressions.
_ARTICLE_RE = re.compile(
    r'<article\s+class=["\'][^"\']*Box-row[^"\']*["\'][^>]*>([\s\S]*?)</article>',
    re.IGNORECASE,
)
_H2_HREF_RE = re.compile(
    r'<h2[^>]*>[\s\S]*?<a\s+[^>]*?href=["\']/([^/"\']+)/([^/"\'\s]+)["\']',
    re.IGNORECASE,
)
_DESC_RE = re.compile(
    r'<p\s+class=["\'][^"\']*col-9[^"\']*["\'][^>]*>([\s\S]*?)</p>',
    re.IGNORECASE,
)
_LANG_RE = re.compile(
    r'itemprop=["\']programmingLanguage["\'][^>]*>([^<]+)</span>',
    re.IGNORECASE,
)
_STARGAZERS_RE = re.compile(
    r'href=["\']/[^"\']+/stargazers["\'][^>]*>([\s\S]*?)</a>',
    re.IGNORECASE,
)
_FORKS_RE = re.compile(
    r'href=["\']/[^"\']+/forks["\'][^>]*>([\s\S]*?)</a>',
    re.IGNORECASE,
)
_PERIOD_STARS_RE = re.compile(
    r'([\d,]+)\s+stars?\s+(?:today|this\s+week|this\s+month)',
    re.IGNORECASE,
)
_AVATAR_RE = re.compile(
    r'<img\s+[^>]*?class=["\'][^"\']*avatar-user[^"\']*["\'][^>]*?src=["\']([^"\']+)["\']',
    re.IGNORECASE,
)

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")
_COUNT_RE = re.compile(r"(\d+(?:\.\d+)?)\s*([km])?")


def strip_html_tags(raw: str) -> str:
    return decode_html_entities(_WS_RE.sub(" ", _TAG_RE.sub(" ", raw)))


def decode_html_entities(raw: str) -> str:
    return (
        raw.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
    )


def parse_compact_count(raw: str) -> int:
    """Parse ``1,234`` / ``12.3k`` / ``4.5m`` into an integer."""
    clean = raw.lower().replace(",", "").strip()
    if not clean:
        return 0
    match = _COUNT_RE.search(clean)
    if not match:
        return 0
    base = float(match.group(1))
    suffix = match.group(2)
    if suffix == "k":
        return int(base * 1_000)
    if suffix == "m":
        return int(base * 1_000_000)
    return int(base)


def normalise_language_key(language: str) -> str:
    key = (language or "all").strip().lower()
    aliases = {
        "": "all",
        "c#": "csharp",
        "c-sharp": "csharp",
        "c++": "c++",
        "cpp": "c++",
        "objective-c": "objective-c",
        "objc": "objective-c",
        "ts": "typescript",
        "js": "javascript",
        "py": "python",
        "golang": "go",
    }
    return aliases.get(key, key)


def trending_url(web_base: str, language: str, period: str) -> str:
    from urllib.parse import quote

    key = normalise_language_key(language)
    segment = "" if key == "all" else "/" + quote(key, safe="+")
    since = period.strip().lower() if period.strip().lower() in SUPPORTED_PERIODS else "daily"
    return f"{web_base.rstrip('/')}/trending{segment}?since={since}"


def parse_trending_html(
    html: str,
    period: str = "daily",
    default_language: str = "",
) -> list[dict]:
    """Parse GitHub's trending board into structured repository records."""
    if not html or not html.strip():
        return []

    results: list[dict] = []
    for article in _ARTICLE_RE.finditer(html):
        block = article.group(1)
        slug_match = _H2_HREF_RE.search(block)
        if not slug_match:
            continue
        owner = slug_match.group(1).strip()
        name = slug_match.group(2).strip()
        if not owner or not name or owner in ("sponsors", "login"):
            continue

        desc_match = _DESC_RE.search(block)
        description = strip_html_tags(desc_match.group(1)).strip() if desc_match else ""

        lang_match = _LANG_RE.search(block)
        language = lang_match.group(1).strip() if lang_match else ""
        if not language:
            language = default_language or "Kotlin"

        star_match = _STARGAZERS_RE.search(block)
        stars = parse_compact_count(strip_html_tags(star_match.group(1))) if star_match else 0

        fork_match = _FORKS_RE.search(block)
        forks = parse_compact_count(strip_html_tags(fork_match.group(1))) if fork_match else 0

        period_match = _PERIOD_STARS_RE.search(block)
        stars_in_period = parse_compact_count(period_match.group(1)) if period_match else 0

        avatars: list[str] = []
        for avatar in _AVATAR_RE.finditer(block):
            src = avatar.group(1).strip()
            if src.startswith("http") and src not in avatars:
                avatars.append(src)
            if len(avatars) >= 5:
                break

        results.append(
            {
                "slug": f"{owner}/{name}".lower(),
                "owner": owner,
                "name": name,
                "description": description,
                "stars": stars,
                "forks": forks,
                "language": decode_html_entities(language),
                "html_url": f"https://github.com/{owner}/{name}",
                "default_branch": "main",
                "topics": [],
                "stars_in_period": stars_in_period,
                "trending_period": period,
                "trending_rank": len(results) + 1,
                "built_by_avatars": avatars,
                "source": "trending",
            }
        )
    return results
