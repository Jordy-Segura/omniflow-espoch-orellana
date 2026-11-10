"""Flujo completo: horario -> eventos -> sesiones de clase -> alertas -> estado -> reportes."""
import time

from app import database
from app.repositories import LabRepository
from app.services.occupancy_service import OccupancyService
from app.schemas import OccupancyEventIn
from conftest import ms, token_for

MONDAY = (2026, 11, 9)


def add_slot(client, auth, start_h=10, end_h=12, day=1, subject="Programación Avanzada", enrolled=28):
    r = client.post("/api/v1/labs/LAB-C1/schedule", headers=auth, json={
        "subject": subject, "teacher": "Ing. Carlos García", "parallel": "4°A",
        "dayOfWeek": day, "startMinute": start_h * 60, "endMinute": end_h * 60, "enrolled": enrolled,
    })
    assert r.status_code == 201, r.text
    return r.json()


def ingest(events, current_ms):
    with database.SessionLocal() as db:
        items = [OccupancyEventIn(lab_code=c, timestamp=t, count=n, device_model="test") for c, t, n in events]
        return OccupancyService(db).ingest_batch(items, current_ms=current_ms)


def test_schedule_crud_and_overlap(client, auth):
    add_slot(client, auth)
    r = client.post("/api/v1/labs/LAB-C1/schedule", headers=auth, json={
        "subject": "Otra", "dayOfWeek": 1, "startMinute": 11 * 60, "endMinute": 13 * 60})
    assert r.status_code == 409
    slots = client.get("/api/v1/labs/LAB-C1/schedule", headers=auth).json()
    assert len(slots) == 1
    assert client.delete(f"/api/v1/labs/LAB-C1/schedule/{slots[0]['id']}", headers=auth).status_code == 204


def test_alerts_from_rules_with_cooldown(client, auth):
    add_slot(client, auth)
    t = ms(*MONDAY, 10, 20)
    # Sobreaforo durante la clase (31 > 30)
    r = ingest([("LAB-C1", t, 31)], current_ms=t)
    assert [a.type for a in r.alerts] == ["SOBREAFORO"]
    # Mismo problema 2 minutos despues: enfriamiento de 10 min, no se repite
    t2 = t + 2 * 60_000
    assert ingest([("LAB-C1", t2, 32)], current_ms=t2).alerts == []
    # Ocupado sin clase a las 15:00
    t3 = ms(*MONDAY, 15, 0)
    assert [a.type for a in ingest([("LAB-C1", t3, 4)], current_ms=t3).alerts] == ["FUERA_DE_HORARIO"]

    alerts = client.get("/api/v1/alerts", headers=auth).json()
    assert {a["type"] for a in alerts} == {"SOBREAFORO", "FUERA_DE_HORARIO"}
    assert alerts[0]["labCode"] == "LAB-C1" and alerts[0]["title"]
    r = client.post(f"/api/v1/alerts/{alerts[0]['id']}/read", headers=auth)
    assert r.json()["read"] is True
    assert len(client.get("/api/v1/alerts?unread=true", headers=auth).json()) == 1


def test_teacher_absence_and_early_exit(client, auth):
    add_slot(client, auth)
    t = ms(*MONDAY, 10, 16)
    assert [a.type for a in ingest([("LAB-C1", t, 0)], current_ms=t).alerts] == ["AUSENCIA_DOCENTE"]

    # Otro lunes: clase normal, luego todos salen a las 11:00 (fin 12:00)
    add_slot(client, auth, 14, 16, subject="Bases de Datos")
    base = ms(*MONDAY, 14, 5)
    events = [("LAB-C1", base + i * 60_000, 20) for i in range(0, 50, 5)]
    events += [("LAB-C1", ms(*MONDAY, 15, 0) + i * 60_000, 0) for i in range(0, 7)]
    last = events[-1][1]
    res = ingest(events, current_ms=last)
    assert [a.type for a in res.alerts] == ["SALIDA_ANTICIPADA"]


def test_stale_batch_does_not_raise_alerts(client, auth):
    add_slot(client, auth)
    t = ms(*MONDAY, 10, 30)
    res = ingest([("LAB-C1", t, 40)], current_ms=t + 60 * 60_000)  # llega 1 hora tarde
    assert res.accepted == 1 and res.alerts == []


def test_status_endpoint_and_forecast(client, auth):
    now = int(time.time() * 1000)
    batch = [{"labCode": "LAB-C2", "timestamp": now - (5 - i) * 60_000, "count": 5 + i, "entries": 0, "exits": 0,
              "source": "CAMERA_PHONE", "deviceModel": "p"} for i in range(6)]
    client.post("/api/v1/occupancy-events/batch", json=batch)
    status = {s["code"]: s for s in client.get("/api/v1/labs/status", headers=auth).json()}
    assert status["LAB-C2"]["occupancy"] == 10
    assert status["LAB-C1"]["occupancy"] is None
    fc = client.get("/api/v1/labs/LAB-C2/forecast?horizon=10", headers=auth).json()
    assert fc["samples"] == 6 and fc["predicted"] >= 12
    pts = client.get("/api/v1/labs/LAB-C2/occupancy?minutes=30", headers=auth).json()
    assert [p["count"] for p in pts] == [5, 6, 7, 8, 9, 10]


def test_weekly_report_and_csv(client, auth):
    slot = add_slot(client, auth, enrolled=20)
    now = int(time.time() * 1000)
    from app.services.occupancy_service import local_dt
    dt = local_dt(now)
    # Clase "hoy" que empezo hace un rato: se crea un bloque en el dia/hora actual si cabe
    start = max(0, dt.hour * 60 + dt.minute - 30)
    r = client.post("/api/v1/labs/LAB-C3/schedule", headers=auth, json={
        "subject": "Química General", "dayOfWeek": dt.isoweekday(), "startMinute": start,
        "endMinute": min(24 * 60, start + 120), "enrolled": 20})
    assert r.status_code == 201, r.text
    ingest([("LAB-C3", now - 60_000, 15), ("LAB-C3", now, 18)], current_ms=now)

    rep = client.get("/api/v1/reports/weekly?days=7", headers=auth).json()
    qm = next(p for p in rep["perLab"] if p["labCode"] == "LAB-C3")
    assert qm["attendancePct"] == 90 and qm["peak"] == 18
    assert rep["classesRegistered"] == 1 and rep["totalUses"] == 1
    assert len(rep["byWeekday"]) == 7

    csv = client.get("/api/v1/reports/occupancy.csv", headers=auth)
    assert csv.status_code == 200 and "LAB-C3" in csv.text
    assert csv.text.splitlines()[0].startswith("lab_code,")
    assert slot["id"] > 0


def test_lab_admin_crud(client):
    admin = token_for(client, "ADMINISTRADOR")
    r = client.post("/api/v1/labs", headers=admin, json={"code": "LAB-FIS", "name": "Laboratorio de Física", "capacity": 20})
    assert r.status_code == 201
    assert client.post("/api/v1/labs", headers=admin, json={"code": "LAB-FIS", "name": "Dup", "capacity": 20}).status_code == 409
    r = client.put("/api/v1/labs/LAB-FIS", headers=admin, json={"code": "LAB-FIS", "name": "Física", "capacity": 24})
    assert r.json()["capacity"] == 24
    with database.SessionLocal() as db:
        assert LabRepository(db).by_code("LAB-FIS").name == "Física"
