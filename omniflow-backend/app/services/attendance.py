"""Asistencia automatica por estudiante.

- La lista de clase sale de OASIS (slot_students: alumnos de la materia/paralelo de ese bloque).
- La camara del laboratorio envia fotogramas; cada rostro reconocido de un estudiante INSCRITO marca
  PRESENTE (o ATRASO si llega despues de `attendance_late_minutes`).
- Un estudiante sin registro: PENDIENTE mientras la clase no termina; AUSENTE cuando ya termino.
- El docente puede corregir manualmente (MANUAL nunca se sobrescribe con lo automatico).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

import numpy as np
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import AttendanceRecord, ClassSession, FaceTemplate, Lab, Person, ScheduleSlot, SlotStudent
from app.services.occupancy_service import local_dt, now_ms
from app.vision.faces import FacePolicy, engine, l2n, match_faces

STATUSES = ("PRESENTE", "ATRASO", "AUSENTE", "JUSTIFICADO")


@dataclass
class StudentRow:
    cedula: str
    nombres: str
    apellidos: str
    email: str
    status: str            # PRESENTE | ATRASO | AUSENTE | JUSTIFICADO | PENDIENTE
    method: str            # ROSTRO | MANUAL | ""
    first_seen: int | None
    confidence: float
    face_enrolled: bool


@dataclass
class ClassAttendance:
    slot: ScheduleSlot
    lab_code: str
    day: date
    state: str             # PROGRAMADA | EN_CURSO | FINALIZADA
    students: list[StudentRow] = field(default_factory=list)
    people_detected: int | None = None

    def count(self, *st: str) -> int:
        return sum(1 for s in self.students if s.status in st)


def class_state(slot: ScheduleSlot, day: date, current_ms: int) -> str:
    now = local_dt(current_ms)
    if day > now.date():
        return "PROGRAMADA"
    if day < now.date():
        return "FINALIZADA"
    m = now.hour * 60 + now.minute
    if m < slot.start_minute:
        return "PROGRAMADA"
    return "EN_CURSO" if m < slot.end_minute else "FINALIZADA"


def roster(db: Session, slot_id: int) -> list[Person]:
    q = (select(Person).join(SlotStudent, SlotStudent.cedula == Person.cedula)
         .where(SlotStudent.slot_id == slot_id).order_by(Person.apellidos, Person.nombres))
    return list(db.scalars(q))


def class_attendance(db: Session, slot: ScheduleSlot, day: date, current_ms: int | None = None) -> ClassAttendance:
    current_ms = current_ms or now_ms()
    lab = db.get(Lab, slot.lab_id)
    state = class_state(slot, day, current_ms)
    records = {r.cedula: r for r in db.scalars(select(AttendanceRecord).where(
        AttendanceRecord.slot_id == slot.id, AttendanceRecord.epoch_day == day.toordinal()))}
    enrolled_faces = set(db.scalars(select(FaceTemplate.cedula).distinct()))
    out = ClassAttendance(slot, lab.code if lab else "", day, state)
    for p in roster(db, slot.id):
        r = records.get(p.cedula)
        if r:
            status, method, first, conf = r.status, r.method, r.first_seen_ms, r.confidence
        else:
            status, method, first, conf = ("AUSENTE" if state == "FINALIZADA" else "PENDIENTE"), "", None, 0.0
        out.students.append(StudentRow(p.cedula, p.nombres, p.apellidos, p.email, status, method, first, conf,
                                       p.cedula in enrolled_faces))
    session = db.scalar(select(ClassSession).where(ClassSession.slot_id == slot.id, ClassSession.epoch_day == day.toordinal()))
    out.people_detected = session.peak_count if session else None
    return out


def slots_for_day(db: Session, day: date, lab_code: str | None = None) -> list[ScheduleSlot]:
    q = select(ScheduleSlot).join(Lab).where(ScheduleSlot.day_of_week == day.isoweekday())
    if lab_code:
        q = q.where(Lab.code == lab_code)
    return list(db.scalars(q.order_by(ScheduleSlot.start_minute, Lab.code)))


def current_slot(db: Session, lab: Lab, ts_ms: int) -> ScheduleSlot | None:
    dt = local_dt(ts_ms)
    m = dt.hour * 60 + dt.minute
    early = get_settings().attendance_early_minutes
    for s in db.scalars(select(ScheduleSlot).where(ScheduleSlot.lab_id == lab.id,
                                                   ScheduleSlot.day_of_week == dt.isoweekday())):
        if s.start_minute - early <= m < s.end_minute:
            return s
    return None


def mark(db: Session, slot: ScheduleSlot, day: date, cedula: str, ts_ms: int, confidence: float,
         method: str = "ROSTRO", status: str | None = None, by: str = "") -> AttendanceRecord:
    r = db.scalar(select(AttendanceRecord).where(AttendanceRecord.slot_id == slot.id,
                                                 AttendanceRecord.epoch_day == day.toordinal(),
                                                 AttendanceRecord.cedula == cedula))
    if method == "ROSTRO":
        dt = local_dt(ts_ms)
        late = (dt.hour * 60 + dt.minute) - slot.start_minute >= get_settings().attendance_late_minutes
        auto_status = "ATRASO" if late else "PRESENTE"
        if r is None:
            r = AttendanceRecord(slot_id=slot.id, epoch_day=day.toordinal(), cedula=cedula, status=auto_status,
                                 method="ROSTRO", first_seen_ms=ts_ms, confidence=confidence, detections=0)
            db.add(r)
        elif r.method == "MANUAL":
            return r  # la correccion del docente manda
        r.last_seen_ms = ts_ms
        r.detections = (r.detections or 0) + 1
        r.confidence = max(r.confidence or 0.0, confidence)
    else:
        if status not in STATUSES:
            raise ValueError(f"Estado inválido. Usa: {', '.join(STATUSES)}")
        if r is None:
            r = AttendanceRecord(slot_id=slot.id, epoch_day=day.toordinal(), cedula=cedula, status=status,
                                 method="MANUAL", first_seen_ms=ts_ms if status in ("PRESENTE", "ATRASO") else None)
            db.add(r)
        r.status, r.method, r.updated_by = status, "MANUAL", by
    return r


# ----------------------------------------------------------------------------- reconocimiento
@dataclass
class FrameResult:
    lab_code: str
    slot: ScheduleSlot | None
    faces: int
    recognized: list[dict]
    unknown: int
    policy: str


def gallery_for(db: Session, cedulas: list[str]) -> dict[str, np.ndarray]:
    g: dict[str, list[np.ndarray]] = {}
    if not cedulas:
        return {}
    for t in db.scalars(select(FaceTemplate).where(FaceTemplate.cedula.in_(cedulas))):
        g.setdefault(t.cedula, []).append(l2n(np.array(json.loads(t.embedding), dtype=np.float32)))
    return {c: np.stack(v) for c, v in g.items()}


def process_frame(db: Session, lab: Lab, image: bytes, ts_ms: int | None = None) -> FrameResult:
    ts_ms = ts_ms or now_ms()
    slot = current_slot(db, lab, ts_ms)
    img = engine.decode(image)
    faces = engine.detect(img)
    if slot is None:
        return FrameResult(lab.code, None, len(faces), [], len(faces), engine.policy.source)
    students = {p.cedula: p for p in roster(db, slot.id)}
    matches = match_faces([f.embedding for f in faces], gallery_for(db, list(students)), engine.policy)
    day = local_dt(ts_ms).date()
    recognized = []
    for m in matches:
        rec = mark(db, slot, day, m.cedula, ts_ms, m.similarity)
        p = students[m.cedula]
        recognized.append({"cedula": m.cedula, "nombre": f"{p.nombres} {p.apellidos}".strip(), "similitud": round(m.similarity, 3),
                           "estado": rec.status, "caja": list(faces[m.face_index].box)})
    db.commit()
    return FrameResult(lab.code, slot, len(faces), recognized, len(faces) - len(matches), engine.policy.source)


def enroll(db: Session, cedula: str, images: list[bytes], by: str, policy: FacePolicy | None = None) -> tuple[int, list[str]]:
    """Registra plantillas (solo embeddings). Cada imagen debe tener exactamente un rostro."""
    if db.get(Person, cedula) is None:
        raise ValueError("La persona no está registrada (sincroniza OASIS primero)")
    problems, added = [], 0
    for i, data in enumerate(images, start=1):
        try:
            faces = engine.detect(engine.decode(data))
        except ValueError as e:
            problems.append(f"Foto {i}: {e}")
            continue
        if len(faces) != 1:
            problems.append(f"Foto {i}: se detectaron {len(faces)} rostros (debe haber exactamente uno)")
            continue
        f = faces[0]
        db.add(FaceTemplate(cedula=cedula, embedding=json.dumps([round(float(x), 6) for x in f.embedding]),
                            quality=f.score, consent_at=datetime.now(timezone.utc), enrolled_by=by))
        added += 1
    db.flush()
    keep = get_settings().face_max_templates
    templates = list(db.scalars(select(FaceTemplate).where(FaceTemplate.cedula == cedula)
                                .order_by(FaceTemplate.created_at.desc(), FaceTemplate.id.desc())))
    for old in templates[keep:]:
        db.delete(old)
    db.commit()
    return added, problems


def parse_day(text: str | None, current_ms: int | None = None) -> date:
    if not text:
        return local_dt(current_ms or now_ms()).date()
    return date.fromisoformat(text)


def week_days(day: date) -> list[date]:
    start = day - timedelta(days=day.isoweekday() - 1)
    return [start + timedelta(days=i) for i in range(7)]
