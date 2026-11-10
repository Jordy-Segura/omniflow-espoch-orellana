"""Conteo de entradas/salidas por linea virtual horizontal (equivalente a LineCrossingCounter.kt)."""
from __future__ import annotations


class LineCrossingCounter:
    def __init__(self, line_position: float = 0.5, down_is_entry: bool = True, margin: float = 0.03) -> None:
        self.line_position = line_position
        self.down_is_entry = down_is_entry
        self.margin = margin
        self.entries = 0
        self.exits = 0
        self._side: dict[int, int] = {}

    def reset(self) -> None:
        self.entries = self.exits = 0
        self._side.clear()

    def update(self, tracks: list[tuple[int, float]]) -> None:
        """tracks: (track_id, centro_y normalizado 0..1) de las personas visibles."""
        alive = set()
        for tid, cy in tracks:
            alive.add(tid)
            if cy < self.line_position - self.margin:
                cur = -1
            elif cy > self.line_position + self.margin:
                cur = 1
            else:
                continue
            prev = self._side.get(tid)
            if prev is not None and prev != cur:
                moved_down = cur > prev
                if moved_down == self.down_is_entry:
                    self.entries += 1
                else:
                    self.exits += 1
            self._side[tid] = cur
        if len(self._side) > 256:
            self._side = {k: v for k, v in self._side.items() if k in alive}
