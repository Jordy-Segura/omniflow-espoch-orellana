"""Administracion de docentes (cuenta + materias) y detalle de una materia con sus estudiantes."""
from sqlalchemy import select

from app import database
from app.models import AttendanceRecord, ScheduleSlot
from app.services.teachers import apply_assignments
from conftest import real_token, token_for
from test_attendance import klass  # noqa: F401 - fixture


def _body(slot_ids, **kw):
    b = {"cedula": "0612345678", "nombres": "maría josé", "apellidos": "pérez lópez", "email": "mperez@espoch.edu.ec",
         "username": "mperez@espoch.edu.ec", "password": "Docente2026", "mustChangePassword": False, "slotIds": slot_ids}
    b.update(kw)
    return b


def test_coordinator_creates_teacher_with_account_and_subjects(client, klass):  # noqa: F811
    coord = real_token(client, "COORDINADOR")
    # El modo demostracion solo consulta: no puede crear cuentas
    assert client.post("/api/v1/admin/teachers", json=_body([klass]), headers=token_for(client, "COORDINADOR")).status_code == 403
    # Docentes y estudiantes no pueden administrar
    assert client.get("/api/v1/admin/teachers", headers=token_for(client, "DOCENTE")).status_code == 403
    assert client.post("/api/v1/admin/teachers", json=_body([klass], password="corta"), headers=coord).status_code == 422
    assert client.post("/api/v1/admin/teachers", json=_body([999]), headers=coord).status_code == 422

    r = client.post("/api/v1/admin/teachers", json=_body([klass]), headers=coord)
    assert r.status_code == 201, r.text
    t = r.json()
    assert t["cedula"] == "061234567-8" and t["username"] == "mperez@espoch.edu.ec" and t["source"] == "MANUAL"
    assert [s["id"] for s in t["slots"]] == [klass] and t["slots"][0]["labCode"] == "LAB-C1"

    # El bloque queda con el nuevo docente y la sincronizacion con OASIS no lo pisa
    with database.SessionLocal() as db:
        slot = db.get(ScheduleSlot, klass)
        slot.teacher, slot.teacher_cedula = "Otro", "000000000-0"
        apply_assignments(db)
        db.commit()
        assert db.get(ScheduleSlot, klass).teacher == "María José Pérez López"

    # El docente inicia sesion con su usuario y contrasena y ve su clase
    tok = client.post("/api/v1/auth/login", json={"username": "mperez@espoch.edu.ec", "password": "Docente2026"}).json()
    h = {"Authorization": f"Bearer {tok['accessToken']}"}
    assert tok["user"]["role"] == "DOCENTE" and tok["user"]["mustChangePassword"] is False
    assert client.get(f"/api/v1/schedule/{klass}/detail", headers=h).status_code == 200

    # Usuario repetido -> 409; editar sin contrasena conserva la actual y puede quitar materias
    other = _body([], cedula="0699999999", username="mperez@espoch.edu.ec")
    assert client.post("/api/v1/admin/teachers", json=other, headers=coord).status_code == 409
    upd = client.put("/api/v1/admin/teachers/0612345678", json=_body([], password=None), headers=coord)
    assert upd.status_code == 200 and upd.json()["slots"] == []
    assert client.post("/api/v1/auth/login", json={"username": "mperez@espoch.edu.ec", "password": "Docente2026"}).status_code == 200
    with database.SessionLocal() as db:
        # Al quitarle la materia, el bloque vuelve al docente que tenia antes de la asignacion
        assert db.get(ScheduleSlot, klass).teacher_cedula == "060000000-1"

    listed = client.get("/api/v1/admin/teachers", headers=coord).json()
    assert any(x["cedula"] == "061234567-8" for x in listed)
    assert client.delete("/api/v1/admin/teachers/061234567-8", headers=coord).status_code == 204
    assert client.post("/api/v1/auth/login", json={"username": "mperez@espoch.edu.ec", "password": "Docente2026"}).status_code == 401


def test_subject_detail_lists_students_with_attendance(client, klass):  # noqa: F811
    with database.SessionLocal() as db:
        db.add_all([AttendanceRecord(slot_id=klass, epoch_day=20000, cedula="060000002-0", status="PRESENTE", method="MANUAL"),
                    AttendanceRecord(slot_id=klass, epoch_day=20007, cedula="060000002-0", status="ATRASO", method="MANUAL"),
                    AttendanceRecord(slot_id=klass, epoch_day=20007, cedula="060000003-0", status="PRESENTE", method="MANUAL")])
        db.commit()
    d = client.get(f"/api/v1/schedule/{klass}/detail", headers=token_for(client, "COORDINADOR")).json()
    assert d["subject"] == "MACHINE LEARNING" and d["labName"] and d["sessions"] == 2 and d["enrolled"] == 3
    by = {s["nombres"]: s for s in d["students"]}
    assert by["ANA"]["attendancePct"] == 100 and by["ANA"]["late"] == 1
    assert by["BRUNO"]["attendancePct"] == 50 and by["BRUNO"]["absent"] == 1
    assert by["CARLA"]["attendancePct"] == 0 and by["CARLA"]["absent"] == 2
    assert d["avgAttendancePct"] == 50
    # Sin sesion -> 401; estudiante -> 403
    assert client.get(f"/api/v1/schedule/{klass}/detail").status_code == 401
    st = client.post("/api/v1/auth/login", json={"username": "ana@espoch.edu.ec", "password": "0600000020"}).json()
    assert client.get(f"/api/v1/schedule/{klass}/detail",
                      headers={"Authorization": f"Bearer {st['accessToken']}"}).status_code == 403
    # El horario publico incluye el id de cada bloque (la app lo usa para abrir el detalle)
    assert any(e["id"] == klass for e in client.get("/api/v1/schedule").json())
    with database.SessionLocal() as db:
        assert db.scalar(select(ScheduleSlot.id).where(ScheduleSlot.id == klass)) == klass


def test_oasis_emails_are_cleaned():
    from app.services.oasis_sync import clean_email
    assert clean_email("mailto:QCarlos@Gmail.com ") == "qcarlos@gmail.com"
    assert clean_email(None) == "" and clean_email("ana@espoch.edu.ec") == "ana@espoch.edu.ec"
