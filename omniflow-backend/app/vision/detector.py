"""Deteccion + seguimiento de personas con YOLOv8 y ByteTrack (Ultralytics).

Las dependencias pesadas (torch, ultralytics, opencv) son opcionales: la API funciona sin ellas
y el modulo de vision reporta "no disponible".
"""
from __future__ import annotations

import importlib.util
import threading
from dataclasses import dataclass
from typing import Any, Protocol

PERSON_CLASS = 0  # COCO: 0 = person


def vision_available() -> bool:
    return importlib.util.find_spec("ultralytics") is not None and importlib.util.find_spec("cv2") is not None


@dataclass(frozen=True)
class Track:
    id: int
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 normalizados
    score: float

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2


class PersonTracker(Protocol):
    name: str

    def track(self, frame: Any) -> tuple[list[Track], Any]:
        """Devuelve (tracks confirmados, frame anotado)."""


_model_lock = threading.Lock()


class YoloByteTrack:
    """YOLOv8 (CNN de una etapa, COCO) + ByteTrack (Kalman + asociacion en dos etapas)."""

    def __init__(self, model_path: str, conf: float, imgsz: int, device: str) -> None:
        from ultralytics import YOLO  # import diferido

        with _model_lock:  # la primera carga descarga los pesos; evitar descargas paralelas
            self.model = YOLO(model_path)
        self.conf = conf
        self.imgsz = imgsz
        self.device = device
        self.name = f"YOLOv8 ({model_path}) + ByteTrack"

    def track(self, frame: Any) -> tuple[list[Track], Any]:
        results = self.model.track(
            frame, persist=True, tracker="bytetrack.yaml", classes=[PERSON_CLASS],
            conf=self.conf, imgsz=self.imgsz, device=self.device, verbose=False,
        )
        r = results[0]
        tracks: list[Track] = []
        boxes = r.boxes
        if boxes is not None and boxes.id is not None:
            ids = boxes.id.int().tolist()
            xyxyn = boxes.xyxyn.tolist()
            scores = boxes.conf.tolist()
            tracks = [Track(i, tuple(b), s) for i, b, s in zip(ids, xyxyn, scores)]
        return tracks, r.plot()
