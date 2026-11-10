"""Comunicacion en tiempo real: los clientes se suscriben a `/ws/live` y reciben ocupacion y alertas."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import WebSocket

log = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self) -> None:
        self._clients: set[WebSocket] = set()
        self._loop: asyncio.AbstractEventLoop | None = None

    def bind_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    @property
    def client_count(self) -> int:
        return len(self._clients)

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self._clients.add(ws)

    def disconnect(self, ws: WebSocket) -> None:
        self._clients.discard(ws)

    async def broadcast(self, message: dict[str, Any]) -> None:
        data = json.dumps(message, ensure_ascii=False)
        dead = []
        for ws in list(self._clients):
            try:
                await ws.send_text(data)
            except Exception:  # cliente desconectado
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)

    def publish(self, message: dict[str, Any]) -> None:
        """Publica desde cualquier hilo (endpoints sync, workers de vision)."""
        loop = self._loop
        if loop is None or loop.is_closed() or not self._clients:
            return
        try:
            asyncio.run_coroutine_threadsafe(self.broadcast(message), loop)
        except RuntimeError:  # pragma: no cover - loop detenido
            log.debug("No se pudo publicar en WebSocket")


manager = ConnectionManager()
