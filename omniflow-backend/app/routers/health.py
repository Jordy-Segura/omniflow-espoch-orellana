from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.schemas import HealthOut
from app.vision import vision_manager

router = APIRouter(prefix="/api/v1", tags=["salud"])


@router.get("/health", response_model=HealthOut)
def health(db: Session = Depends(get_db)) -> HealthOut:
    """Contrato con la app: `{"status": "ok"}` (HealthDto). Campos extra son informativos."""
    try:
        db.execute(text("SELECT 1"))
        database = "ok"
    except Exception:  # noqa: BLE001
        database = "error"
    return HealthOut(status="ok", database=database, oasis_configured=get_settings().oasis_configured,
                     vision_available=vision_manager.available())
