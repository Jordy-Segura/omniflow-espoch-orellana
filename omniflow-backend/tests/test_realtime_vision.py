import time
from pathlib import Path

import pytest

from app.vision import vision_manager
from app.vision.detector import Track, vision_available
from app.vision.line_counter import LineCrossingCounter
from app.vision.worker import CameraConfig, CameraWorker


def test_websocket_receives_occupancy_and_alerts(client, auth):
    token = auth["Authorization"].split()[1]
    with client.websocket_connect(f"/ws/live?token={token}") as ws:
        assert ws.receive_json()["type"] == "hello"
        now = int(time.time() * 1000)
        client.post("/api/v1/occupancy-events/batch", json=[{
            "labCode": "LAB-C2", "timestamp": now, "count": 45, "entries": 0, "exits": 0,
            "source": "CAMERA_PHONE", "deviceModel": "p"}])
        msgs = [ws.receive_json() for _ in range(2)]
    occ = next(m for m in msgs if m["type"] == "occupancy")
    assert occ["labCode"] == "LAB-C2" and occ["count"] == 45
    alert = next(m["alert"] for m in msgs if m["type"] == "alert")
    assert alert["labCode"] == "LAB-C2" and alert["type"] in {"SOBREAFORO", "FUERA_DE_HORARIO"}


def test_websocket_rejects_bad_token(client):
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/live?token=malo") as ws:
            ws.receive_json()


def test_line_counter():
    c = LineCrossingCounter(0.5, down_is_entry=True)
    for i in range(31):
        c.update([(1, 0.2 + i * 0.02)])
    assert (c.entries, c.exits) == (1, 0)
    for i in range(31):
        c.update([(1, 0.8 - i * 0.02)])
    assert (c.entries, c.exits) == (1, 1)


class FakeCapture:
    def __init__(self, frames=40):
        self.left = frames

    def isOpened(self):
        return True

    def read(self):
        if self.left <= 0:
            return False, None
        self.left -= 1
        time.sleep(0.005)
        return True, object()

    def release(self):
        pass


class FakeTracker:
    name = "fake"

    def __init__(self):
        self.frame = 0

    def track(self, frame):
        self.frame += 1
        y = min(0.9, 0.1 + self.frame * 0.03)  # una persona que baja y cruza la linea
        return [Track(1, (0.4, y - 0.1, 0.5, y + 0.1), 0.9), Track(2, (0.1, 0.1, 0.2, 0.3), 0.8)], None


def test_camera_worker_counts_and_records(settings):
    settings.vision_sample_seconds = 0
    records = []
    cfg = CameraConfig(1, "LAB-C1", "fake://", counting_line=True, line_position=0.5)
    w = CameraWorker(cfg, lambda *a: records.append(a), tracker_factory=FakeTracker,
                     capture_factory=lambda url: FakeCapture())
    w.start()
    deadline = time.time() + 5
    while time.time() < deadline and w.status.entries < 1:
        time.sleep(0.05)
    w.stop()
    w.join(timeout=5)
    assert not w.is_alive()
    assert w.status.entries == 1 and w.status.people == 2
    assert records and records[-1][0] == "LAB-C1" and records[-1][1] == 2
    assert sum(r[2] for r in records) == 1  # deltas de entradas suman 1


def test_camera_api_without_vision_running(client, auth):
    r = client.post("/api/v1/cameras", headers=auth, json={
        "labCode": "LAB-C1", "name": "Teléfono puerta", "url": "http://192.168.1.50:8080/video"})
    assert r.status_code == 201, r.text
    cam = r.json()
    st = client.get(f"/api/v1/cameras/{cam['id']}/status", headers=auth).json()
    assert st["running"] is False
    assert client.get(f"/api/v1/cameras/{cam['id']}/snapshot.jpg", headers=auth).status_code == 404
    assert client.delete(f"/api/v1/cameras/{cam['id']}", headers=auth).status_code == 204
    vision_manager.stop_all()


@pytest.mark.skipif(not vision_available(), reason="requirements-vision.txt no instalado")
def test_yolov8_bytetrack_detects_people_in_sample_image():
    """Prueba real del modelo: YOLOv8n + ByteTrack sobre la imagen de ejemplo de Ultralytics (bus.jpg)."""
    import cv2
    from ultralytics.utils import ASSETS

    from app.vision.detector import YoloByteTrack

    img = cv2.imread(str(Path(ASSETS) / "bus.jpg"))
    assert img is not None
    tracker = YoloByteTrack("yolov8n.pt", conf=0.35, imgsz=640, device="cpu")
    tracks, annotated = tracker.track(img)
    assert len(tracks) >= 3, tracks
    assert len({t.id for t in tracks}) == len(tracks)  # IDs unicos de ByteTrack
    assert annotated.shape == img.shape
    # Segundo frame igual: ByteTrack mantiene los mismos IDs
    tracks2, _ = tracker.track(img)
    assert {t.id for t in tracks2} == {t.id for t in tracks}
