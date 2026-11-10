"""Asistencia automatica: emparejamiento (puro), cuentas propias y flujo real con reconocimiento facial."""
import base64
from datetime import date
from pathlib import Path

import numpy as np
import pytest

from app import database
from app.models import Lab, Person, ScheduleSlot, SlotStudent
from app.services import attendance as att
from app.services.users import ensure_accounts, hash_password, verify_password
from app.vision.faces import FacePolicy, engine, l2n, match_faces
from conftest import ms, token_for

POL = FacePolicy(threshold=0.36, margin=0.04)


def vec(seed, noise=0.0, base=None):
    rng = np.random.default_rng(seed)
    v = base if base is not None else rng.normal(size=128)
    return l2n(v + rng.normal(scale=noise / np.sqrt(128), size=128))  # norma del ruido ~= noise


def test_match_open_set_one_to_one_and_margin():
    a, b, c = vec(1), vec(2), vec(3)
    gallery = {"A": np.stack([a, vec(10, 0.3, a)]), "B": np.stack([b])}
    faces = [vec(20, 0.25, a), vec(21, 0.25, b), c]   # c = desconocido (no inscrito)
    m = {x.cedula: x.face_index for x in match_faces(faces, gallery, POL)}
    assert m == {"A": 0, "B": 1}
    # Dos rostros muy parecidos a A: solo uno se asigna (un estudiante no puede estar dos veces).
    m2 = match_faces([vec(30, 0.1, a), vec(31, 0.1, a)], gallery, POL)
    assert len(m2) == 1 and m2[0].cedula == "A"
    # Ambiguo: dos estudiantes casi identicos -> no se acepta por margen.
    twin = vec(40, 0.01, a)
    assert match_faces([vec(41, 0.1, a)], {"A": a[None], "T": twin[None]}, POL) == []


def test_password_hash_compatible_format():
    h = hash_password("Clave2026")
    assert h.startswith("scrypt$") and len(h.split("$")) == 3
    assert verify_password("Clave2026", h) and not verify_password("otra", h)


@pytest.fixture
def klass(client):
    """Clase de prueba lunes 10:00-12:00 en LAB-C1 con 3 estudiantes inscritos."""
    with database.SessionLocal() as db:
        lab = db.query(Lab).filter_by(code="LAB-C1").one()
        slot = ScheduleSlot(lab_id=lab.id, subject="MACHINE LEARNING", teacher="Docente Prueba", parallel="6°1",
                            day_of_week=1, start_minute=600, end_minute=720, enrolled=3, career_code="TI", level=6,
                            teacher_cedula="060000000-1", source="PDF")
        db.add(slot)
        db.add(Person(cedula="060000000-1", nombres="DOCENTE", apellidos="PRUEBA", email="docente@espoch.edu.ec", role="DOCENTE"))
        for i, (n, a) in enumerate([("ANA", "ALVAREZ"), ("BRUNO", "BRAVO"), ("CARLA", "CASTRO")]):
            db.add(Person(cedula=f"06000000{i + 2}-0", nombres=n, apellidos=a, email=f"{n.lower()}@espoch.edu.ec",
                          role="ESTUDIANTE", level=6, career_code="TI"))
        db.flush()
        for i in range(3):
            db.add(SlotStudent(slot_id=slot.id, cedula=f"06000000{i + 2}-0"))
        assert ensure_accounts(db) == 4
        db.commit()
        return slot.id


MONDAY = date(2026, 11, 9)


def test_own_account_login_forced_password_change_and_roles(client, klass):
    r = client.post("/api/v1/auth/login", json={"username": "ana@espoch.edu.ec", "password": "0600000020"})
    assert r.status_code == 200, r.text
    tok = r.json()
    assert tok["user"]["role"] == "ESTUDIANTE" and tok["user"]["mustChangePassword"] is True
    h = {"Authorization": f"Bearer {tok['accessToken']}"}
    # Tambien por cedula, y clave incorrecta -> 401
    assert client.post("/api/v1/auth/login", json={"username": "060000002-0", "password": "0600000020"}).status_code == 200
    assert client.post("/api/v1/auth/login", json={"username": "ana@espoch.edu.ec", "password": "x"}).status_code == 401
    weak = client.post("/api/v1/auth/change-password", headers=h, json={"currentPassword": "0600000020", "newPassword": "0600000020"})
    assert weak.status_code == 422
    ok = client.post("/api/v1/auth/change-password", headers=h, json={"currentPassword": "0600000020", "newPassword": "Asistencia2026"})
    assert ok.status_code == 200 and ok.json()["user"]["mustChangePassword"] is False
    assert client.post("/api/v1/auth/login", json={"username": "ana@espoch.edu.ec", "password": "Asistencia2026"}).status_code == 200
    # Un estudiante no ve listas de clase
    assert client.get(f"/api/v1/attendance/slot/{klass}?date={MONDAY}", headers=h).status_code == 403


def test_class_list_manual_marks_and_states(client, klass):
    teacher = client.post("/api/v1/auth/login", json={"username": "docente@espoch.edu.ec", "password": "0600000001"}).json()
    h = {"Authorization": f"Bearer {teacher['accessToken']}"}
    past = "2026-11-02"  # lunes anterior: clase finalizada -> sin registro = AUSENTE
    d = client.get(f"/api/v1/attendance/slot/{klass}?date={past}", headers=h).json()
    assert d["state"] == "FINALIZADA" and d["absent"] == 3 and d["total"] == 3
    r = client.put(f"/api/v1/attendance/slot/{klass}/students/060000002-0?date={past}", headers=h, json={"status": "justificado"})
    assert r.status_code == 200 and r.json()["status"] == "JUSTIFICADO" and r.json()["method"] == "MANUAL"
    d = client.get(f"/api/v1/attendance/slot/{klass}?date={past}", headers=h).json()
    assert (d["justified"], d["absent"]) == (1, 2)
    assert client.get(f"/api/v1/attendance/slot/{klass}?date=2026-11-03", headers=h).status_code == 422  # martes
    classes = client.get(f"/api/v1/attendance/classes?date={past}&mine=true", headers=h).json()
    assert [c["slotId"] for c in classes] == [klass]
    other = token_for(client, "COORDINADOR")
    assert client.get(f"/api/v1/attendance/slot/{klass}?date={past}", headers=other).status_code == 200


def _crop_faces():
    cv2 = pytest.importorskip("cv2")
    ultra = pytest.importorskip("ultralytics.utils")
    img = cv2.imread(str(Path(ultra.ASSETS) / "bus.jpg"))
    faces = engine.detect(img)
    assert len(faces) >= 2
    crops = []
    for f in sorted(faces, key=lambda f: f.box[0])[:2]:
        x, y, w, h = f.box
        pad = int(w * 0.8)
        crop = img[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
        crops.append([cv2.imencode(".jpg", c)[1].tobytes() for c in (crop, cv2.flip(crop, 1))])
    return img, crops


@pytest.mark.skipif(not engine.available(), reason="modelos YuNet/SFace no disponibles")
def test_face_enrollment_and_automatic_attendance(client, klass):
    cv2 = pytest.importorskip("cv2")
    img, crops = _crop_faces()
    teacher = client.post("/api/v1/auth/login", json={"username": "docente@espoch.edu.ec", "password": "0600000001"}).json()
    h = {"Authorization": f"Bearer {teacher['accessToken']}"}

    # Sin consentimiento no se registra
    b64 = lambda x: base64.b64encode(x).decode()  # noqa: E731
    r = client.post("/api/v1/faces/enroll", headers=h, json={"cedula": "060000002-0", "consent": False, "images": [b64(crops[0][0])]})
    assert r.status_code == 400
    for ced, imgs in zip(["060000002-0", "060000003-0"], crops):
        r = client.post("/api/v1/faces/enroll", headers=h, json={"cedula": ced, "consent": True, "images": [b64(i) for i in imgs]})
        assert r.status_code == 200, r.text
        assert r.json()["added"] == 2

    # Fotograma completo durante la clase (lunes 10:05) -> ANA y BRUNO presentes; CARLA (sin rostro) pendiente.
    frame = cv2.imencode(".jpg", img)[1].tobytes()
    with database.SessionLocal() as db:
        lab = db.query(Lab).filter_by(code="LAB-C1").one()
        res = att.process_frame(db, lab, frame, ts_ms=ms(2026, 11, 9, 10, 5))
        assert res.slot is not None and {r["cedula"] for r in res.recognized} == {"060000002-0", "060000003-0"}
        assert all(r["estado"] == "PRESENTE" for r in res.recognized)
        # Mas tarde (10:30) -> sigue PRESENTE (no cambia a atraso: ya estaba)
        att.process_frame(db, lab, frame, ts_ms=ms(2026, 11, 9, 10, 30))
        ca = att.class_attendance(db, db.get(ScheduleSlot, klass), MONDAY, current_ms=ms(2026, 11, 9, 12, 30))
    status = {s.cedula: s.status for s in ca.students}
    assert status == {"060000002-0": "PRESENTE", "060000003-0": "PRESENTE", "060000004-0": "AUSENTE"}

    # Fuera de horario: detecta rostros pero no marca a nadie
    with database.SessionLocal() as db:
        lab = db.query(Lab).filter_by(code="LAB-C1").one()
        res = att.process_frame(db, lab, frame, ts_ms=ms(2026, 11, 9, 15, 0))
        assert res.slot is None and res.recognized == [] and res.faces >= 2

    # Derecho de supresion: el estudiante elimina su registro facial
    st = client.post("/api/v1/auth/login", json={"username": "bruno@espoch.edu.ec", "password": "0600000030"}).json()
    hs = {"Authorization": f"Bearer {st['accessToken']}"}
    assert client.get("/api/v1/faces/status", headers=hs).json()["templates"] == 2
    assert client.delete("/api/v1/faces", headers=hs).status_code == 204
    assert client.get("/api/v1/faces/status", headers=hs).json()["templates"] == 0


@pytest.mark.skipif(not engine.available(), reason="modelos YuNet/SFace no disponibles")
def test_enroll_from_enrollment_pdf(client, klass):
    pdf = (Path(__file__).parent / "fixtures" / "matriculas_prueba.pdf").read_bytes()
    teacher = client.post("/api/v1/auth/login", json={"username": "docente@espoch.edu.ec", "password": "0600000001"}).json()
    h = {"Authorization": f"Bearer {teacher['accessToken']}", "Content-Type": "application/pdf"}
    assert client.post("/api/v1/faces/enroll-pdf?consent=false", content=pdf, headers=h).status_code == 400
    assert client.post("/api/v1/faces/enroll-pdf?consent=true", content=b"no pdf", headers=h).status_code == 400
    r = client.post("/api/v1/faces/enroll-pdf?consent=true", content=pdf, headers=h)
    assert r.status_code == 200, r.text
    res = {x["pagina"]: x for x in r.json()["resultados"]}
    assert res[1]["estado"] == "REGISTRADO" and res[1]["cedula"] == "060000002-0"      # por cedula
    assert res[2]["estado"] == "REGISTRADO" and res[2]["cedula"] == "060000003-0"      # por nombre
    assert res[3]["estado"] == "NO_IDENTIFICADO"                                       # no sincronizado
    assert r.json()["registrados"] == 2

    # Un estudiante solo puede registrar su propia matricula
    st = client.post("/api/v1/auth/login", json={"username": "carla@espoch.edu.ec", "password": "0600000040"}).json()
    hs = {"Authorization": f"Bearer {st['accessToken']}", "Content-Type": "application/pdf"}
    r = client.post("/api/v1/faces/enroll-pdf?consent=true", content=pdf, headers=hs).json()
    assert {x["estado"] for x in r["resultados"]} <= {"NO_AUTORIZADO", "NO_IDENTIFICADO"} and r["registrados"] == 0
