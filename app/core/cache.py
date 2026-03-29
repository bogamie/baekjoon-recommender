"""
Optional Redis cache layer.

If REDIS_URL is not configured the cache silently falls back to a
plain in-process dict so the app starts without Redis at all.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)

# Populated lazily; avoids import errors when redis isn't installed.
_redis_client: Optional[Any] = None
_memory_cache: dict[str, tuple[Any, float]] = {}
_memory_counters: dict[str, tuple[int, float]] = {}


def _get_redis():
    global _redis_client
    if _redis_client is not None:
        return _redis_client
    try:
        import redis
        from app.core.config import get_settings
        settings = get_settings()
        if settings.REDIS_URL:
            _redis_client = redis.from_url(settings.REDIS_URL, decode_responses=True)
            _redis_client.ping()
            logger.info("Redis cache connected: %s", settings.REDIS_URL)
    except Exception as exc:
        logger.warning("Redis unavailable, falling back to in-memory cache: %s", exc)
        _redis_client = None
    return _redis_client


def cache_get(key: str) -> Optional[Any]:
    r = _get_redis()
    if r:
        raw = r.get(key)
        return json.loads(raw) if raw else None
    # in-memory fallback
    import time
    entry = _memory_cache.get(key)
    if entry and time.time() < entry[1]:
        return entry[0]
    return None


def cache_set(key: str, value: Any, ttl: int = 300) -> None:
    r = _get_redis()
    if r:
        r.setex(key, ttl, json.dumps(value))
        return
    import time
    _memory_cache[key] = (value, time.time() + ttl)


def cache_delete(key: str) -> None:
    r = _get_redis()
    if r:
        r.delete(key)
    _memory_cache.pop(key, None)
    _memory_counters.pop(key, None)


def cache_set_if_absent(key: str, value: Any, ttl: int = 300) -> bool:
    r = _get_redis()
    if r:
        return bool(r.set(key, json.dumps(value), ex=ttl, nx=True))

    import time

    now = time.time()
    entry = _memory_cache.get(key)
    if entry and now < entry[1]:
        return False
    _memory_cache[key] = (value, now + ttl)
    return True


def cache_incr(key: str, ttl: int = 60) -> int:
    """
    Increment counter and ensure expiry.
    Returns the incremented value.
    """
    r = _get_redis()
    if r:
        pipe = r.pipeline()
        pipe.incr(key)
        pipe.ttl(key)
        count, current_ttl = pipe.execute()
        if current_ttl is None or current_ttl < 0:
            r.expire(key, ttl)
        return int(count)

    import time

    now = time.time()
    count, expires_at = _memory_counters.get(key, (0, now + ttl))
    if now >= expires_at:
        count = 0
        expires_at = now + ttl
    count += 1
    _memory_counters[key] = (count, expires_at)
    return count
