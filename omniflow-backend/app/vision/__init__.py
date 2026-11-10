"""Gestor de camaras procesadas en el servidor."""
from __future__ import annotations

import logging
import threading

from app.database import SessionLocal
from app.models import Camera
from app.repositories import CameraRepository, LabRepository
from app.services.occupancy_service import OccupancyService
from app.vision.detector import vision_available
from app.vision.worker import CameraConfig, CameraStatus, CameraWorker

log = logging.getLogger(__name__)


def record_reading(lab_code: str, people: int, entries: int, exits: int) -> None:
    with SessionLocal() as db:
        lab = LabRepository(db).by_code(lab_code)
        if lab is None:
            raise ValueError(f"Laboratorio {lab_code} no existe")
        OccupancyService(db).record(lab, people, entries, exits, source="BACKEND_VISION", device="omniflow-backend")


class VisionManager:
    def __init__(self) -> None:
        self._workers: dict[int, CameraWorker] = {}
        self._lock = threading.Lock()

    @staticmethod
    def available() -> bool:
        return vision_available()

    def start(self, cam: Camera, worker_factory=None) -> CameraWorker:
        cfg = CameraConfig(cam.id, cam.lab.code, cam.url, cam.counting_line, cam.line_position, cam.down_is_entry)
        with self._lock:
            old = self._workers.get(cam.id)
            if old is not None and old.is_alive():
                old.stop()
            worker = worker_factory(cfg) if worker_factory else CameraWorker(cfg, record_reading)
            self._workers[cam.id] = worker
            worker.start()
            return worker

    def stop(self, cam_id: int) -> None:
        with self._lock:
            w = self._workers.pop(cam_id, None)
        if w is not None:
            w.stop()
            w.join(timeout=5)

    def status(self, cam_id: int) -> CameraStatus:
        w = self._workers.get(cam_id)
        return w.status if w is not None else CameraStatus()

    def snapshot(self, cam_id: int) -> bytes | None:
        w = self._workers.get(cam_id)
        return w.snapshot if w is not None else None

    def start_enabled(self) -> int:
        if not vision_available():
            log.warning("Módulo de visión no disponible: instala requirements-vision.txt")
            return 0
        with SessionLocal() as db:
            cams = [c for c in CameraRepository(db).list() if c.enabled]
            for c in cams:
                _ = c.lab.code  # cargar relacion antes de cerrar la sesion
                self.start(c)
        return len(cams)

    def stop_all(self) -> None:
        for cam_id in list(self._workers):
            self.stop(cam_id)


vision_manager = VisionManager()
