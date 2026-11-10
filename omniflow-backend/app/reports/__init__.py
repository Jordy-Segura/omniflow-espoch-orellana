"""Reportes de uso, asistencia y ocupacion para la toma de decisiones."""
from __future__ import annotations

import csv
import io
from collections import defaultdict
from datetime import date, datetime

from sqlalchemy.orm import Session

from app.repositories import AlertRepository, LabRepository, OccupancyRepository, ScheduleRepository, SessionRepository
from app.schemas import LabAttendanceOut, LateArrivalOut, WeeklyReportOut
from app.services.occupancy_service import local_dt, now_ms

LATE_THRESHOLD_MIN = 10


def weekly_report(db: Session, days: int = 7, current_ms: int | None = None) -> WeeklyReportOut:
    current_ms = current_ms or now_ms()
    since_ms = current_ms - days * 24 * 3600 * 1000
    today = local_dt(current_ms).date()
    labs = LabRepository(db).list()
    slots = {s.id: s for s in ScheduleRepository(db).all()}
    sessions = SessionRepository(db).from_day(today.toordinal() - days)
    events = OccupancyRepository(db).all_since(since_ms)

    avg_cap = (sum(l.capacity for l in labs) / len(labs)) if labs else 1
    by_day: dict[int, list[int]] = defaultdict(list)
    by_lab: dict[int, list[int]] = defaultdict(list)
    for e in events:
        by_day[local_dt(e.timestamp_ms).isoweekday()].append(e.count)
        by_lab[e.lab_id].append(e.count)
    weekday = [min(100, round((sum(v) / len(v)) / avg_cap * 100)) if (v := by_day.get(d)) else 0 for d in range(1, 8)]

    per_lab = []
    for lab in labs:
        ratios = []
        for s in sessions:
            slot = slots.get(s.slot_id)
            if s.lab_id == lab.id and s.samples > 0 and slot and slot.enrolled > 0:
                ratios.append(min(1.0, s.peak_count / slot.enrolled))
        counts = by_lab.get(lab.id, [])
        per_lab.append(LabAttendanceOut(
            lab_code=lab.code, lab_name=lab.name,
            attendance_pct=round(sum(ratios) / len(ratios) * 100) if ratios else 0,
            avg_occupancy_pct=min(100, round((sum(counts) / len(counts)) / max(lab.capacity, 1) * 100)) if counts else 0,
            peak=max(counts, default=0),
        ))
    per_lab.sort(key=lambda x: x.attendance_pct, reverse=True)

    lab_names = {l.id: l.name for l in labs}
    late = []
    for s in sessions:
        slot = slots.get(s.slot_id)
        if slot is None or s.first_presence_ms is None:
            continue
        t = local_dt(s.first_presence_ms)
        minutes_late = t.hour * 60 + t.minute - slot.start_minute
        if minutes_late >= LATE_THRESHOLD_MIN:
            late.append(LateArrivalOut(lab_name=lab_names.get(s.lab_id, ""), subject=slot.subject, teacher=slot.teacher,
                                       date=date.fromordinal(s.epoch_day).isoformat(), minutes_late=minutes_late))
    late.sort(key=lambda x: x.date, reverse=True)

    return WeeklyReportOut(
        days=days,
        avg_occupancy_pct=round(sum(p.avg_occupancy_pct for p in per_lab) / len(per_lab)) if per_lab else 0,
        total_uses=sum(1 for s in sessions if s.first_presence_ms is not None),
        classes_registered=len(sessions),
        alerts=AlertRepository(db).count_since(since_ms),
        by_weekday=weekday,
        per_lab=per_lab,
        late_arrivals=late,
        early_exits=sum(1 for s in sessions if s.ended_early),
    )


def occupancy_csv(db: Session, days: int = 7) -> str:
    since_ms = now_ms() - days * 24 * 3600 * 1000
    codes = {l.id: l.code for l in LabRepository(db).list(only_active=False)}
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["lab_code", "fecha_hora_local", "timestamp_ms", "personas", "entradas", "salidas", "fuente", "dispositivo"])
    for e in OccupancyRepository(db).all_since(since_ms):
        dt: datetime = local_dt(e.timestamp_ms)
        w.writerow([codes.get(e.lab_id, e.lab_id), dt.strftime("%Y-%m-%d %H:%M:%S"), e.timestamp_ms, e.count,
                    e.entries, e.exits, e.source, e.device])
    return buf.getvalue()
