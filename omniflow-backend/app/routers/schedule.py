import json

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import MANAGERS, STAFF
from app.database import SessionLocal, get_db
from collections import Counter

from sqlalchemy import func

from app.auth import current_user
from app.models import AttendanceRecord, FaceTemplate, Lab, Person, ScheduleSlot, SlotStudent, SyncRun
from app.repositories import audit
from app.routers.attendance import _can_see_class
from app.schemas import (PersonOut, ScheduleEntryOut, ScheduleSlotOut, SubjectDetailOut, SubjectStudentOut,
                         SyncRunOut, UserOut)
from app.services import oasis_sync

router = APIRouter(prefix="/api/v1", tags=["horarios y OASIS"])


@router.get("/schedule", response_model=list[ScheduleEntryOut])
def full_schedule(db: Session = Depends(get_db)) -> list[ScheduleEntryOut]:
    """Horario de todos los laboratorios con docente y numero de inscritos (lo descarga la app).
    Publico: no incluye datos de estudiantes."""
    rows = db.execute(select(ScheduleSlot, Lab.code).join(Lab).order_by(Lab.code, ScheduleSlot.day_of_week,
                                                                           ScheduleSlot.start_minute)).all()
    return [ScheduleEntryOut(**ScheduleSlotOut.model_validate(slot).model_dump(), lab_code=code) for slot, code in rows]


@router.get("/schedule/{slot_id}/students", response_model=list[PersonOut])
def slot_students(slot_id: int, db: Session = Depends(get_db), _: UserOut = Depends(STAFF)) -> list[Person]:
    """Estudiantes inscritos (OASIS) en la materia del bloque: base para el control de asistencia."""
    if db.get(ScheduleSlot, slot_id) is None:
        raise HTTPException(404, "Bloque de horario no encontrado")
    q = select(Person).join(SlotStudent, SlotStudent.cedula == Person.cedula).where(SlotStudent.slot_id == slot_id)
    return list(db.scalars(q.order_by(Person.apellidos, Person.nombres)))


@router.get("/schedule/{slot_id}/detail", response_model=SubjectDetailOut)
def subject_detail(slot_id: int, db: Session = Depends(get_db), user: UserOut = Depends(current_user)) -> SubjectDetailOut:
    """Materia de un bloque: datos academicos, estudiantes inscritos y su asistencia acumulada."""
    slot = db.get(ScheduleSlot, slot_id)
    if slot is None:
        raise HTTPException(404, "Bloque de horario no encontrado")
    if user.role == "ESTUDIANTE" or not _can_see_class(user, slot):
        raise HTTPException(403, "Solo el docente de la materia, coordinadores y administradores ven esta información")
    q = select(Person).join(SlotStudent, SlotStudent.cedula == Person.cedula).where(SlotStudent.slot_id == slot_id)
    people = list(db.scalars(q.order_by(Person.apellidos, Person.nombres)))
    ceds = [p.cedula for p in people]
    faces = set(db.scalars(select(FaceTemplate.cedula).where(FaceTemplate.cedula.in_(ceds)).distinct())) if ceds else set()
    sessions = db.scalar(select(func.count(func.distinct(AttendanceRecord.epoch_day)))
                         .where(AttendanceRecord.slot_id == slot_id)) or 0
    counts: dict[str, Counter] = {}
    for ced, status in db.execute(select(AttendanceRecord.cedula, AttendanceRecord.status)
                                  .where(AttendanceRecord.slot_id == slot_id)):
        counts.setdefault(ced, Counter())[status] += 1
    students, pcts = [], []
    for p in people:
        c = counts.get(p.cedula, Counter())
        attended = c["PRESENTE"] + c["ATRASO"] + c["JUSTIFICADO"]
        pct = round(attended * 100 / sessions) if sessions else None
        if pct is not None:
            pcts.append(pct)
        students.append(SubjectStudentOut(
            cedula=p.cedula, nombres=p.nombres, apellidos=p.apellidos, email=p.email, face_enrolled=p.cedula in faces,
            present=c["PRESENTE"], late=c["ATRASO"], justified=c["JUSTIFICADO"],
            absent=max(sessions - attended, c["AUSENTE"]), attendance_pct=pct))
    return SubjectDetailOut(
        id=slot.id, lab_code=slot.lab.code, lab_name=slot.lab.name, subject=slot.subject, subject_code=slot.subject_code,
        teacher=slot.teacher, teacher_email=slot.teacher_email, parallel=slot.parallel, career_code=slot.career_code,
        level=slot.level, group=slot.group, period_code=slot.period_code, day_of_week=slot.day_of_week,
        start_minute=slot.start_minute, end_minute=slot.end_minute, enrolled=len(people) or slot.enrolled,
        sessions=sessions, faces_enrolled=len(faces), avg_attendance_pct=round(sum(pcts) / len(pcts)) if pcts else None,
        students=students)


@router.get("/people", response_model=list[PersonOut])
def people(role: str | None = Query(None, pattern="^(DOCENTE|ESTUDIANTE)$"), q: str | None = None,
           limit: int = Query(200, ge=1, le=2000), db: Session = Depends(get_db),
           _: UserOut = Depends(MANAGERS)) -> list[Person]:
    stmt = select(Person).order_by(Person.apellidos, Person.nombres).limit(limit)
    if role:
        stmt = stmt.where(Person.role == role)
    if q:
        like = f"%{q.upper()}%"
        stmt = stmt.where((Person.apellidos.ilike(like)) | (Person.nombres.ilike(like)) | (Person.cedula.like(f"%{q}%")))
    return list(db.scalars(stmt))


def _run_out(r: SyncRun) -> SyncRunOut:
    return SyncRunOut(id=r.id, status=r.status, started_at=r.started_at.isoformat(),
                      finished_at=r.finished_at.isoformat() if r.finished_at else None,
                      report=json.loads(r.report) if r.report else {})


@router.post("/admin/oasis-sync", response_model=SyncRunOut, status_code=202)
def start_sync(db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS)) -> SyncRunOut:
    """Sincroniza docentes, estudiantes e inscritos de los bloques de laboratorio desde OASIS (en segundo plano)."""
    run = SyncRun(status="RUNNING")
    db.add(run)
    audit(db, user.username, "OASIS_SYNC", "")
    db.commit()
    oasis_sync.start_background(SessionLocal, run.id)
    return _run_out(run)


@router.get("/admin/oasis-sync", response_model=SyncRunOut | None)
def last_sync(db: Session = Depends(get_db), _: UserOut = Depends(MANAGERS)) -> SyncRunOut | None:
    r = db.scalar(select(SyncRun).order_by(SyncRun.id.desc()))
    return _run_out(r) if r else None
