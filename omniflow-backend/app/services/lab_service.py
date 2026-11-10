"""Estado consolidado de los laboratorios (horario + ultima lectura de camara)."""
from __future__ import annotations

from sqlalchemy.orm import Session

from app.models import ScheduleSlot
from app.repositories import LabRepository, OccupancyRepository, ScheduleRepository
from app.schemas import ForecastOut, LabStatusOut, ScheduleSlotOut
from app.services import analytics, rules
from app.services.occupancy_service import local_dt, now_ms

STALE_MS = 3 * 60 * 1000


def slot_out(s: ScheduleSlot | None) -> ScheduleSlotOut | None:
    return ScheduleSlotOut.model_validate(s) if s is not None else None


def lab_statuses(db: Session, current_ms: int | None = None) -> list[LabStatusOut]:
    current_ms = current_ms or now_ms()
    dt = local_dt(current_ms)
    labs = LabRepository(db).list()
    latest = OccupancyRepository(db).latest_per_lab()
    sched = ScheduleRepository(db).all()
    out = []
    for lab in labs:
        ctx = rules.schedule_context([s for s in sched if s.lab_id == lab.id], dt)
        ev = latest.get(lab.id)
        fresh = ev is not None and current_ms - ev.timestamp_ms < STALE_MS
        occ = ev.count if fresh else None
        out.append(LabStatusOut(
            code=lab.code, name=lab.name, capacity=lab.capacity,
            status=rules.lab_status(lab.capacity, ctx, occ).value,
            occupancy=occ, last_update=ev.timestamp_ms if ev else None,
            current_class=slot_out(ctx.current), next_class=slot_out(ctx.next), minutes_to_next=ctx.minutes_to_next,
        ))
    return out


def lab_forecast(db: Session, lab_id: int, capacity: int, horizon: int = 15) -> ForecastOut | None:
    since = now_ms() - 30 * 60 * 1000
    events = [e for e in OccupancyRepository(db).since(lab_id, since) if e.source != "DEMO"]
    fc = analytics.forecast([(e.timestamp_ms, e.count) for e in events], horizon, capacity)
    if fc is None:
        return None
    return ForecastOut(horizon_minutes=fc.horizon_minutes, predicted=fc.predicted,
                       trend_per_hour=round(fc.trend_per_hour, 2), r2=round(fc.r2, 3), samples=fc.samples)
