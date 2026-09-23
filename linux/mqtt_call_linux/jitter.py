"""Bounded packet jitter buffer with three-batch startup and rebuffering."""

from __future__ import annotations

import collections
import threading


class JitterBuffer:
    def __init__(self, startup_batches: int = 3, max_batches: int = 10):
        self.startup_batches = startup_batches
        self.max_batches = max_batches
        self._batches: collections.deque[tuple[bytes, list[bytes]]] = collections.deque()
        self._lock = threading.Lock()
        self._playing = False
        self._released = False
        self._session: bytes | None = None

    def offer(self, session: bytes, frames: list[bytes]) -> None:
        if not frames:
            return
        with self._lock:
            if self._session is not None and session != self._session:
                if self._batches:
                    return
                self._playing = False
                self._released = False
            self._session = session
            if len(self._batches) >= self.max_batches:
                self._batches.popleft()
            self._batches.append((session, frames))
            if len(self._batches) >= self.startup_batches:
                self._playing = True

    def release(self, session: bytes) -> None:
        with self._lock:
            if session == self._session:
                self._released = True
                if self._batches:
                    self._playing = True
                else:
                    self._reset()

    def next_batch(self, timeout: float = 0.1) -> tuple[bytes, list[bytes]] | None:
        with self._lock:
            if not self._playing:
                return None
            if self._batches:
                return self._batches.popleft()
            self._playing = False
            if self._released:
                self._reset()
            return None

    def state(self) -> tuple[str, int]:
        with self._lock:
            return ("playing" if self._playing else "buffering", len(self._batches))

    def clear(self) -> None:
        with self._lock:
            self._reset()

    def _reset(self) -> None:
        self._batches.clear()
        self._playing = False
        self._released = False
        self._session = None

