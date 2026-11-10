from fastapi import APIRouter, Body, Depends
from sqlalchemy.orm import Session

from app.auth import device_key
from app.database import get_db
from app.schemas import BatchResultOut, OccupancyEventIn
from app.services.occupancy_service import OccupancyService

router = APIRouter(prefix="/api/v1/occupancy-events", tags=["ocupación"])

MAX_BATCH = 1000


@router.post("/batch", response_model=BatchResultOut, dependencies=[Depends(device_key)])
def post_batch(events: list[OccupancyEventIn] = Body(..., max_length=MAX_BATCH), db: Session = Depends(get_db)) -> BatchResultOut:
    """Contrato con la app Android (`OmniFlowApi.postEvents`): recibe una lista de OccupancyEventDto
    y responde `{"accepted": n}`. Es idempotente: reenviar el mismo lote no duplica registros."""
    r = OccupancyService(db).ingest_batch(events)
    return BatchResultOut(accepted=r.accepted, duplicates=r.duplicates, rejected=r.rejected, unknown_labs=r.unknown_labs)
