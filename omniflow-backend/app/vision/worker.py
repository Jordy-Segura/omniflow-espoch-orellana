"""Un hilo por camara: lee el stream con OpenCV, ejecuta YOLOv8 + ByteTrack y registra la ocupacion."""
from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.config import get_settings
from app.services.analytics import MedianSmoother
from app.vision.detector import PersonTracker, YoloByteTrack, vision_available
from app.vision.line_counter import LineCrossingCounter

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class CameraConfig:
    id: int
    lab_code: str
    url: str
    counting_line: bool = False
    line_position: float = 0.5
    down_is_entry: bool = True


@dataclass
class CameraStatus:
    running: bool = False
    connected: bool = False
    people: int = 0
    entries: int = 0
    exits: int = 0
    fps: float = 0.0
    inference_ms: float = 0.0
    last_error: str | None = None
    model: str = ""


#: Callback que persiste una lectura: (lab_code, personas, entradas_delta, salidas_delta)
RecordFn = Callable[[str, int, int, int], None]


def open_capture(url: str) -> Any:
    import cv2

    source: Any = int(url) if url.strip().isdigit() else url
    cap = cv2.VideoCapture(source)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    return cap


class CameraWorker(threading.Thread):
    def __init__(
        self,
        cfg: CameraConfig,
        record: RecordFn,
        tracker_factory: Callable[[], PersonTracker] | None = None,
        capture_factory: Callable[[str], Any] = open_capture,
    ) -> None:
        super().__init__(name=f"camera-{cfg.id}", daemon=True)
        self.cfg = cfg
        self.status = CameraStatus()
        self._record = record
        self._stop_event = threading.Event()
        self._tracker_factory = tracker_factory
        self._capture_factory = capture_factory
        self._last_jpeg: bytes | None = None
        self._lock = threading.Lock()

    def stop(self) -> None:
        self._stop_event.set()

    @property
    def snapshot(self) -> bytes | None:
        with self._lock:
            return self._last_jpeg

    def run(self) -> None:  # noqa: C901 - bucle principal
        s = get_settings()
        self.status.running = True
        try:
            if self._tracker_factory is not None:
                tracker = self._tracker_factory()
            else:
                if not vision_available():
                    raise RuntimeError("Instala requirements-vision.txt (ultralytics, opencv) para usar cámaras en el servidor")
                tracker = YoloByteTrack(s.yolo_model, s.yolo_confidence, s.yolo_imgsz, s.yolo_device)
            self.status.model = tracker.name
        except Exception as e:  # noqa: BLE001
            log.exception("No se pudo cargar el modelo")
            self.status.last_error = str(e)
            self.status.running = False
            return

        smoother = MedianSmoother()
        counter = LineCrossingCounter(self.cfg.line_position, self.cfg.down_is_entry)
        last_save = 0.0
        saved_entries = saved_exits = 0
        cap = None
        last_ts = 0.0
        try:
            while not self._stop_event.is_set():
                if cap is None:
                    cap = self._capture_factory(self.cfg.url)
                    if not cap.isOpened():
                        self._fail(f"No se pudo abrir {self.cfg.url}")
                        cap = None
                        self._stop_event.wait(3)
                        continue
                ok, frame = cap.read()
                if not ok or frame is None:
                    # Fin de un archivo de video: volver a empezar; stream caido: reconectar.
                    cap.release()
                    cap = None
                    self._fail("Se perdió la señal de la cámara, reconectando…")
                    self._stop_event.wait(1)
                    continue
                self.status.connected = True
                self.status.last_error = None

                t0 = time.perf_counter()
                tracks, annotated = tracker.track(frame)
                self.status.inference_ms = round((time.perf_counter() - t0) * 1000, 1)

                people = smoother.add(len(tracks))
                if self.cfg.counting_line:
                    counter.update([(t.id, t.cy) for t in tracks])
                self.status.people = people
                self.status.entries, self.status.exits = counter.entries, counter.exits

                now = time.perf_counter()
                if last_ts:
                    inst = 1.0 / max(now - last_ts, 1e-3)
                    self.status.fps = round(inst if not self.status.fps else self.status.fps * 0.85 + inst * 0.15, 2)
                last_ts = now
                self._store_jpeg(annotated)

                if time.time() - last_save >= s.vision_sample_seconds:
                    last_save = time.time()
                    try:
                        self._record(self.cfg.lab_code, people, counter.entries - saved_entries, counter.exits - saved_exits)
                        saved_entries, saved_exits = counter.entries, counter.exits
                    except Exception as e:  # noqa: BLE001
                        log.exception("No se pudo registrar la lectura")
                        self.status.last_error = f"Error guardando lectura: {e}"
        finally:
            if cap is not None:
                cap.release()
            self.status.running = False
            self.status.connected = False

    def _fail(self, msg: str) -> None:
        self.status.connected = False
        self.status.last_error = msg

    def _store_jpeg(self, frame: Any) -> None:
        if frame is None:
            return
        try:
            import cv2

            ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 75])
            if ok:
                with self._lock:
                    self._last_jpeg = buf.tobytes()
        except Exception:  # noqa: BLE001 - la vista previa es opcional
            pass
