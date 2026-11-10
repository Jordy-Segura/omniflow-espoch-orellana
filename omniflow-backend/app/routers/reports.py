from fastapi import APIRouter, Depends, Query
from fastapi.responses import PlainTextResponse
from sqlalchemy.orm import Session

from app.auth import STAFF
from app.database import get_db
from app.reports import occupancy_csv, weekly_report
from app.schemas import UserOut, WeeklyReportOut

router = APIRouter(prefix="/api/v1/reports", tags=["reportes"])


@router.get("/weekly", response_model=WeeklyReportOut)
def weekly(days: int = Query(7, ge=1, le=90), db: Session = Depends(get_db), _: UserOut = Depends(STAFF)) -> WeeklyReportOut:
    return weekly_report(db, days)


@router.get("/occupancy.csv", response_class=PlainTextResponse)
def occupancy_export(days: int = Query(7, ge=1, le=365), db: Session = Depends(get_db), _: UserOut = Depends(STAFF)):
    return PlainTextResponse(
        occupancy_csv(db, days), media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="ocupacion_{days}d.csv"'},
    )
