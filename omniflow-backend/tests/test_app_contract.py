"""Contrato exacto que consume la app Android (`OmniFlowApi.kt`)."""
import time


def test_health_matches_health_dto(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_labs_match_lab_dto_and_app_codes(client):
    r = client.get("/api/v1/labs")
    assert r.status_code == 200
    labs = r.json()
    assert {l["code"] for l in labs} == {"LAB-C1", "LAB-C2", "LAB-C3", "LAB-C4"}
    for lab in labs:
        assert set(lab) == {"code", "name", "building", "floor", "capacity"}


def app_event(code="LAB-C1", ts=None, count=12):
    """Mismo JSON que serializa kotlinx.serialization para OccupancyEventDto."""
    return {
        "labCode": code,
        "timestamp": ts or int(time.time() * 1000),
        "count": count,
        "entries": 1,
        "exits": 0,
        "source": "CAMERA_PHONE",
        "deviceModel": "Google Pixel 8",
    }


def test_batch_accepts_app_payload_and_is_idempotent(client):
    now = int(time.time() * 1000)
    batch = [app_event(ts=now - 20_000), app_event(ts=now - 10_000, count=13), app_event("LAB-C2", now)]
    r = client.post("/api/v1/occupancy-events/batch", json=batch)
    assert r.status_code == 200, r.text
    assert r.json()["accepted"] == 3

    # La app reintenta si no recibio respuesta: no debe duplicar.
    r2 = client.post("/api/v1/occupancy-events/batch", json=batch)
    assert r2.json()["accepted"] == 0
    assert r2.json()["duplicates"] == 3


def test_batch_reports_unknown_labs_and_invalid_timestamps(client):
    now = int(time.time() * 1000)
    r = client.post("/api/v1/occupancy-events/batch",
                    json=[app_event("LAB-XX", now), app_event(ts=now + 3_600_000), app_event(ts=now)])
    body = r.json()
    assert body["accepted"] == 1
    assert body["rejected"] == 2
    assert body["unknownLabs"] == ["LAB-XX"]


def test_batch_validation_error_for_negative_count(client):
    r = client.post("/api/v1/occupancy-events/batch", json=[app_event(count=-1)])
    assert r.status_code == 422


def test_device_key_is_enforced_when_configured(client, settings):
    settings.device_api_key = "clave-telefonos"
    r = client.post("/api/v1/occupancy-events/batch", json=[app_event()])
    assert r.status_code == 401
    r = client.post("/api/v1/occupancy-events/batch", json=[app_event()], headers={"X-Device-Key": "clave-telefonos"})
    assert r.status_code == 200
