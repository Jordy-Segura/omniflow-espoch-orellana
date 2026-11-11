"""Asistencia por estudiante (automatica por rostro + correccion manual) y registro facial con consentimiento."""
import base64
import binascii

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app.auth import MANAGERS, STAFF, STAFF_REAL, USER_REAL, current_user, decode_token, device_key
from app.config import get_settings
from app.database import get_db
from app.models import AttendanceRecord, FaceTemplate, Lab, Person, ScheduleSlot
from app.repositories import LabRepository, audit
from app.schemas import (
    AttendanceMarkIn, AttendanceStudentOut, ClassAttendanceOut, ClassSummaryOut, EnrollIn, EnrollOut,
    FaceStatusOut, FrameResultOut, MyAttendanceOut, RecognizedOut, UserOut,
)
from app.services import attendance as att
from app.services.occupancy_service import now_ms
from app.services.users import digits
from app.vision.faces import engine

router = APIRouter(prefix="/api/v1", tags=["asistencia"])


def _summary(ca: att.ClassAttendance, db: Session) -> dict:
    s = ca.slot
    return dict(
        slot_id=s.id, lab_code=ca.lab_code, subject=s.subject, teacher=s.teacher, parallel=s.parallel,
        career_code=s.career_code, level=s.level, date=ca.day.isoformat(), start_minute=s.start_minute,
        end_minute=s.end_minute, state=ca.state, total=len(ca.students),
        present=ca.count("PRESENTE"), late=ca.count("ATRASO"), absent=ca.count("AUSENTE"),
        pending=ca.count("PENDIENTE"), justified=ca.count("JUSTIFICADO"),
        faces_enrolled=sum(1 for x in ca.students if x.face_enrolled), people_detected=ca.people_detected,
    )


def _can_see_class(user: UserOut, slot: ScheduleSlot) -> bool:
    if user.role in ("ADMINISTRADOR", "COORDINADOR") or user.source == "DEMO":
        return True
    return bool(user.cedula) and digits(user.cedula) == digits(slot.teacher_cedula)


@router.get("/attendance/classes", response_model=list[ClassSummaryOut])
def classes(date: str | None = None, lab: str | None = None, mine: bool = False,
            db: Session = Depends(get_db), user: UserOut = Depends(STAFF)) -> list[dict]:
    """Clases de laboratorio de un dia con su resumen de asistencia. `mine=true`: solo las del docente."""
    day = att.parse_day(date)
    out = []
    for slot in att.slots_for_day(db, day, lab):
        if mine and not _can_see_class(user, slot):
            continue
        out.append(_summary(att.class_attendance(db, slot, day), db))
    return out


@router.get("/attendance/slot/{slot_id}", response_model=ClassAttendanceOut)
def class_detail(slot_id: int, date: str | None = None, db: Session = Depends(get_db),
                 user: UserOut = Depends(STAFF)) -> dict:
    """Lista de estudiantes de la asignatura: quienes estan y quienes faltan."""
    slot = db.get(ScheduleSlot, slot_id)
    if slot is None:
        raise HTTPException(404, "Clase no encontrada")
    if not _can_see_class(user, slot):
        raise HTTPException(403, "Solo el docente de la clase o coordinación pueden ver esta lista")
    day = att.parse_day(date)
    if day.isoweekday() != slot.day_of_week:
        raise HTTPException(422, "Esa clase no se dicta en la fecha indicada")
    ca = att.class_attendance(db, slot, day)
    return {**_summary(ca, db), "students": [AttendanceStudentOut(**vars(s)) for s in ca.students]}


@router.put("/attendance/slot/{slot_id}/students/{cedula}", response_model=AttendanceStudentOut)
def mark_manual(slot_id: int, cedula: str, body: AttendanceMarkIn, date: str | None = None,
                db: Session = Depends(get_db), user: UserOut = Depends(STAFF_REAL)) -> AttendanceStudentOut:
    slot = db.get(ScheduleSlot, slot_id)
    if slot is None:
        raise HTTPException(404, "Clase no encontrada")
    if not _can_see_class(user, slot):
        raise HTTPException(403, "Solo el docente de la clase o coordinación pueden corregir la asistencia")
    day = att.parse_day(date)
    if cedula not in {p.cedula for p in att.roster(db, slot_id)}:
        raise HTTPException(404, "El estudiante no está inscrito en esta asignatura")
    try:
        att.mark(db, slot, day, cedula, now_ms(), 1.0, method="MANUAL", status=body.status.upper(), by=user.username)
    except ValueError as e:
        raise HTTPException(422, str(e)) from None
    audit(db, user.username, "ASISTENCIA_MANUAL", f"{slot_id} {day} {cedula} {body.status}")
    db.commit()
    row = next(s for s in att.class_attendance(db, slot, day).students if s.cedula == cedula)
    return AttendanceStudentOut(**vars(row))


def _frame_auth(request: Request, authorization: str | None = Header(default=None),
                x_device_key: str | None = Header(default=None)) -> str:
    """El telefono-camara se autentica con X-Device-Key (si esta configurada) o con el token del docente."""
    if authorization and authorization.lower().startswith("bearer "):
        user = decode_token(authorization.split(" ", 1)[1])
        if user.role == "ESTUDIANTE":
            raise HTTPException(403, "Los estudiantes no pueden enviar fotogramas")
        return user.username
    device_key(x_device_key)
    return "dispositivo"


@router.post("/attendance/frame", response_model=FrameResultOut)
async def frame(request: Request, lab: str = Query(..., alias="labCode"), db: Session = Depends(get_db),
                who: str = Depends(_frame_auth)) -> FrameResultOut:
    """Fotograma JPEG de la camara del laboratorio -> reconoce rostros de los inscritos y marca asistencia."""
    if not engine.available():
        raise HTTPException(503, "Modelos de reconocimiento facial no disponibles en el servidor")
    lab_obj = LabRepository(db).by_code(lab)
    if lab_obj is None:
        raise HTTPException(404, f"Laboratorio {lab} no existe")
    data = await request.body()
    if not data or len(data) > 8 * 1024 * 1024:
        raise HTTPException(400, "Envía una imagen JPEG de hasta 8 MB")
    try:
        r = att.process_frame(db, lab_obj, data)
    except ValueError as e:
        raise HTTPException(400, str(e)) from None
    return FrameResultOut(lab_code=r.lab_code, slot_id=r.slot.id if r.slot else None,
                          subject=r.slot.subject if r.slot else None, faces=r.faces,
                          recognized=[RecognizedOut(**x) for x in r.recognized], unknown=r.unknown, policy=r.policy)


@router.get("/attendance/me", response_model=list[MyAttendanceOut])
def my_attendance(days: int = Query(30, ge=1, le=180), db: Session = Depends(get_db),
                  user: UserOut = Depends(current_user)) -> list[MyAttendanceOut]:
    """Historial del estudiante que inicio sesion (sus clases de laboratorio)."""
    if not user.cedula:
        raise HTTPException(400, "Tu cuenta no tiene cédula asociada")
    today = att.parse_day(None)
    out = []
    from datetime import timedelta
    from app.models import SlotStudent
    my_slots = list(db.scalars(select(ScheduleSlot).join(SlotStudent, SlotStudent.slot_id == ScheduleSlot.id)
                               .where(SlotStudent.cedula == user.cedula)))
    labs = {l.id: l.code for l in db.scalars(select(Lab))}
    recs = {(r.slot_id, r.epoch_day): r for r in db.scalars(select(AttendanceRecord).where(AttendanceRecord.cedula == user.cedula))}
    for i in range(days):
        day = today - timedelta(days=i)
        for s in my_slots:
            if s.day_of_week != day.isoweekday():
                continue
            st = att.class_state(s, day, now_ms())
            r = recs.get((s.id, day.toordinal()))
            status = r.status if r else ("AUSENTE" if st == "FINALIZADA" else "PENDIENTE")
            out.append(MyAttendanceOut(slot_id=s.id, lab_code=labs.get(s.lab_id, ""), subject=s.subject,
                                       date=day.isoformat(), start_minute=s.start_minute, status=status,
                                       method=r.method if r else ""))
    return out


# ------------------------------------------------------------------------------- rostros
def _target_cedula(body_cedula: str | None, user: UserOut, db: Session) -> str:
    if user.role == "ESTUDIANTE" or not body_cedula:
        if not user.cedula:
            raise HTTPException(400, "Tu cuenta no tiene cédula asociada")
        return user.cedula
    if user.role not in ("ADMINISTRADOR", "COORDINADOR", "DOCENTE"):
        raise HTTPException(403, "No puedes registrar el rostro de otra persona")
    ced = body_cedula
    if db.get(Person, ced) is None:
        d = digits(ced)
        ced = f"{d[:9]}-{d[9]}" if len(d) == 10 else ced
    return ced


@router.post("/faces/enroll", response_model=EnrollOut)
def enroll(body: EnrollIn, db: Session = Depends(get_db), user: UserOut = Depends(USER_REAL)) -> EnrollOut:
    """Registra el rostro (3-5 fotos). Solo se guardan los vectores; las fotos se descartan."""
    if not body.consent:
        raise HTTPException(400, "Se requiere el consentimiento expreso para tratar datos biométricos")
    if not engine.available():
        raise HTTPException(503, "Modelos de reconocimiento facial no disponibles en el servidor")
    if not 1 <= len(body.images) <= 8:
        raise HTTPException(422, "Envía entre 1 y 8 fotos")
    cedula = _target_cedula(body.cedula, user, db)
    try:
        images = [base64.b64decode(i.split(",")[-1], validate=True) for i in body.images]
    except (binascii.Error, ValueError):
        raise HTTPException(400, "Las fotos deben ir en base64") from None
    try:
        added, problems = att.enroll(db, cedula, images, by=user.username)
    except ValueError as e:
        raise HTTPException(404, str(e)) from None
    audit(db, user.username, "ROSTRO_REGISTRADO", f"{cedula} +{added}")
    db.commit()
    total = db.scalar(select(func.count(FaceTemplate.id)).where(FaceTemplate.cedula == cedula)) or 0
    if added == 0:
        raise HTTPException(422, "; ".join(problems) or "No se pudo registrar ningún rostro")
    return EnrollOut(cedula=cedula, added=added, templates=total, problems=problems)


@router.post("/faces/enroll-pdf")
async def enroll_pdf(request: Request, consent: bool = Query(...), cedula: str | None = None,
                     db: Session = Depends(get_db), user: UserOut = Depends(USER_REAL)) -> dict:
    """PDF de matricula (una o varias paginas): identifica a cada estudiante por cedula/nombre y registra
    la foto de la matricula como plantilla facial. Estudiante: solo su propia matricula. Docente/coordinacion:
    carga masiva (cada persona debio aceptar el consentimiento)."""
    if not consent:
        raise HTTPException(400, "Se requiere el consentimiento expreso para tratar datos biométricos")
    if not engine.available():
        raise HTTPException(503, "Modelos de reconocimiento facial no disponibles en el servidor")
    data = await request.body()
    if not data or len(data) > 25 * 1024 * 1024:
        raise HTTPException(400, "Envía un PDF de hasta 25 MB")
    if not data.startswith(b"%PDF"):
        raise HTTPException(400, "El archivo no es un PDF")
    from app.services.enrollment_pdf import enroll_from_pdf

    student = user.role == "ESTUDIANTE"
    own = user.cedula if student else None
    fallback = own or (_target_cedula(cedula, user, db) if cedula else None)
    try:
        results = enroll_from_pdf(db, data, by=user.username, allowed_cedula=own, fallback_cedula=fallback)
    except ValueError as e:
        raise HTTPException(422, str(e)) from None
    ok = [r for r in results if r.status == "REGISTRADO"]
    audit(db, user.username, "ROSTRO_PDF", f"{len(ok)}/{len(results)}")
    db.commit()
    return {"registrados": len(ok), "total": len(results),
            "resultados": [{"pagina": r.page, "cedula": r.cedula, "nombre": r.nombre, "estado": r.status, "detalle": r.detail}
                           for r in results]}


@router.get("/faces/status", response_model=FaceStatusOut)
def face_status(cedula: str | None = None, db: Session = Depends(get_db), user: UserOut = Depends(current_user)) -> FaceStatusOut:
    ced = _target_cedula(cedula, user, db)
    ts = list(db.scalars(select(FaceTemplate).where(FaceTemplate.cedula == ced)))
    return FaceStatusOut(cedula=ced, templates=len(ts),
                         consent_at=max(t.consent_at for t in ts).isoformat() if ts else None)


@router.delete("/faces", status_code=204)
def face_delete(cedula: str | None = None, db: Session = Depends(get_db), user: UserOut = Depends(USER_REAL)) -> None:
    """Derecho de supresion (LOPDP): el estudiante (o coordinacion) elimina su registro facial."""
    if cedula and user.role not in ("ADMINISTRADOR", "COORDINADOR"):
        cedula = None
    ced = _target_cedula(cedula, user, db) if cedula else (user.cedula or "")
    if not ced:
        raise HTTPException(400, "Tu cuenta no tiene cédula asociada")
    db.execute(delete(FaceTemplate).where(FaceTemplate.cedula == ced))
    audit(db, user.username, "ROSTRO_ELIMINADO", ced)
    db.commit()


@router.get("/faces/model")
def face_model() -> dict:
    p = engine.policy
    return {"available": engine.available(), "detector": "YuNet 2023mar", "embedder": "SFace 2021dec (128-d)",
            "classifier": "1-NN coseno, conjunto abierto", "threshold": p.threshold, "margin": p.margin,
            "minDetScore": p.min_det_score, "policySource": p.source,
            "lateMinutes": get_settings().attendance_late_minutes}
