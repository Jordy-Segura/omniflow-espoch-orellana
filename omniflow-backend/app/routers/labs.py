from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.auth import MANAGERS, MANAGERS_REAL, current_user
from app.database import get_db
from app.models import Lab, ScheduleSlot
from app.repositories import LabRepository, OccupancyRepository, ScheduleRepository, audit
from app.schemas import ForecastOut, LabIn, LabOut, LabStatusOut, OccupancyPoint, ScheduleSlotIn, ScheduleSlotOut, UserOut
from app.services.lab_service import lab_forecast, lab_statuses
from app.services.occupancy_service import now_ms

router = APIRouter(prefix="/api/v1/labs", tags=["laboratorios"])


def _lab_or_404(db: Session, code: str) -> Lab:
    lab = LabRepository(db).by_code(code)
    if lab is None:
        raise HTTPException(404, f"Laboratorio {code} no existe")
    return lab


@router.get("", response_model=list[LabOut])
def list_labs(db: Session = Depends(get_db)) -> list[Lab]:
    """Contrato con la app (LabDto). Publico: no expone datos personales."""
    return LabRepository(db).list()


@router.post("", response_model=LabOut, status_code=201)
def create_lab(body: LabIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS_REAL)) -> Lab:
    repo = LabRepository(db)
    if repo.by_code(body.code):
        raise HTTPException(409, "Ya existe un laboratorio con ese código")
    lab = repo.add(Lab(**body.model_dump()))
    audit(db, user.username, "LAB_CREADO", body.code)
    db.commit()
    return lab


@router.put("/{code}", response_model=LabOut)
def update_lab(code: str, body: LabIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS_REAL)) -> Lab:
    lab = _lab_or_404(db, code)
    for k, v in body.model_dump(exclude={"code"}).items():
        setattr(lab, k, v)
    audit(db, user.username, "LAB_EDITADO", code)
    db.commit()
    return lab


@router.get("/status", response_model=list[LabStatusOut])
def status(db: Session = Depends(get_db), _: UserOut = Depends(current_user)) -> list[LabStatusOut]:
    """Estado en tiempo real: Libre / Ocupado / Proximo / Fuera de horario / Anomalo."""
    return lab_statuses(db)


@router.get("/{code}", response_model=LabOut)
def get_lab(code: str, db: Session = Depends(get_db)) -> Lab:
    return _lab_or_404(db, code)


@router.get("/{code}/schedule", response_model=list[ScheduleSlotOut])
def get_schedule(code: str, db: Session = Depends(get_db), _: UserOut = Depends(current_user)) -> list[ScheduleSlot]:
    return ScheduleRepository(db).for_lab(_lab_or_404(db, code).id)


@router.post("/{code}/schedule", response_model=ScheduleSlotOut, status_code=201)
def add_slot(code: str, body: ScheduleSlotIn, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS_REAL)) -> ScheduleSlot:
    if body.end_minute <= body.start_minute:
        raise HTTPException(422, "La hora de fin debe ser posterior a la de inicio")
    lab = _lab_or_404(db, code)
    clash = [s for s in ScheduleRepository(db).for_lab(lab.id)
             if s.day_of_week == body.day_of_week and s.start_minute < body.end_minute and body.start_minute < s.end_minute]
    if clash:
        raise HTTPException(409, f"Se cruza con {clash[0].subject} ({clash[0].start_minute // 60:02d}:{clash[0].start_minute % 60:02d})")
    slot = ScheduleRepository(db).add(ScheduleSlot(lab_id=lab.id, source="LOCAL", **body.model_dump()))
    audit(db, user.username, "HORARIO_AGREGADO", f"{code} {body.subject}")
    db.commit()
    return slot


@router.delete("/{code}/schedule/{slot_id}", status_code=204)
def delete_slot(code: str, slot_id: int, db: Session = Depends(get_db), user: UserOut = Depends(MANAGERS_REAL)) -> None:
    lab = _lab_or_404(db, code)
    slot = ScheduleRepository(db).get(slot_id)
    if slot is None or slot.lab_id != lab.id:
        raise HTTPException(404, "Bloque de horario no encontrado")
    db.delete(slot)
    audit(db, user.username, "HORARIO_ELIMINADO", f"{code} {slot.subject}")
    db.commit()


@router.get("/{code}/occupancy", response_model=list[OccupancyPoint])
def occupancy(code: str, minutes: int = Query(60, ge=1, le=7 * 24 * 60), db: Session = Depends(get_db),
              _: UserOut = Depends(current_user)) -> list[OccupancyPoint]:
    lab = _lab_or_404(db, code)
    events = OccupancyRepository(db).since(lab.id, now_ms() - minutes * 60_000)
    return [OccupancyPoint(timestamp=e.timestamp_ms, count=e.count, source=e.source) for e in events]


@router.get("/{code}/forecast", response_model=ForecastOut | None)
def forecast(code: str, horizon: int = Query(15, ge=1, le=120), db: Session = Depends(get_db),
             _: UserOut = Depends(current_user)) -> ForecastOut | None:
    """Prediccion de ocupacion (regresion lineal OLS + Holt) con las lecturas de los ultimos 30 min."""
    lab = _lab_or_404(db, code)
    return lab_forecast(db, lab.id, lab.capacity, horizon)
