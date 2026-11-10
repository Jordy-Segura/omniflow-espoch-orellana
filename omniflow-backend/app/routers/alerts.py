from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import update
from sqlalchemy.orm import Session

from app.auth import current_user
from app.database import get_db
from app.models import Alert
from app.repositories import AlertRepository, LabRepository
from app.schemas import AlertOut, UserOut
from app.services.occupancy_service import alert_to_dict

router = APIRouter(prefix="/api/v1/alerts", tags=["alertas"])


@router.get("", response_model=list[AlertOut])
def list_alerts(
    limit: int = Query(100, ge=1, le=500),
    unread: bool = False,
    lab: str | None = None,
    db: Session = Depends(get_db),
    _: UserOut = Depends(current_user),
) -> list[dict]:
    lab_id = None
    if lab:
        l = LabRepository(db).by_code(lab)
        if l is None:
            raise HTTPException(404, f"Laboratorio {lab} no existe")
        lab_id = l.id
    return [alert_to_dict(a) for a in AlertRepository(db).list(limit, unread, lab_id)]


@router.post("/{alert_id}/read", response_model=AlertOut)
def mark_read(alert_id: int, db: Session = Depends(get_db), _: UserOut = Depends(current_user)) -> dict:
    a = AlertRepository(db).get(alert_id)
    if a is None:
        raise HTTPException(404, "Alerta no encontrada")
    a.read = True
    db.commit()
    return alert_to_dict(a)


@router.post("/read-all")
def mark_all_read(db: Session = Depends(get_db), _: UserOut = Depends(current_user)) -> dict:
    n = db.execute(update(Alert).where(Alert.read.is_(False)).values(read=True)).rowcount
    db.commit()
    return {"updated": n}
