"""Ingesta de eventos de ocupacion: deduplicacion, sesiones de clase, reglas, anomalias y alertas.

Corresponde a los pasos 4-7 de la "Logica operativa del sistema" del Documento Maestro.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Alert, Lab, OccupancyEvent
from app.repositories import AlertRepository, LabRepository, OccupancyRepository, ScheduleRepository, SessionRepository
from app.schemas import OccupancyEventIn
from app.services import analytics, rules
from app.services.rules import ALERT_TITLES, AlertType
from app.websocket import manager

log = logging.getLogger(__name__)

ALERT_COOLDOWN_MS = 10 * 60 * 1000
#: Solo se evaluan reglas para lecturas recientes (no para un lote atrasado que llega tarde).
FRESH_MS = 5 * 60 * 1000
MAX_FUTURE_SKEW_MS = 10 * 60 * 1000


@dataclass
class IngestResult:
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0
    unknown_labs: list[str] = field(default_factory=list)
    alerts: list[Alert] = field(default_factory=list)


def now_ms() -> int:
    return int(time.time() * 1000)


def local_dt(ts_ms: int) -> datetime:
    return datetime.fromtimestamp(ts_ms / 1000, get_settings().tz)


def hour_of_week(dt: datetime) -> int:
    return (dt.isoweekday() - 1) * 24 + dt.hour


class OccupancyService:
    def __init__(self, db: Session) -> None:
        self.db = db
        self.labs = LabRepository(db)
        self.events = OccupancyRepository(db)
        self.schedule = ScheduleRepository(db)
        self.sessions = SessionRepository(db)
        self.alerts = AlertRepository(db)

    # ------------------------------------------------------------------ API publica
    def ingest_batch(self, items: list[OccupancyEventIn], current_ms: int | None = None) -> IngestResult:
        current_ms = current_ms or now_ms()
        res = IngestResult()
        labs = self.labs.by_codes({i.lab_code for i in items})
        latest: dict[int, OccupancyEvent] = {}
        seen: set[tuple[int, int, str]] = set()

        for item in sorted(items, key=lambda i: i.timestamp):
            lab = labs.get(item.lab_code)
            if lab is None:
                res.rejected += 1
                if item.lab_code not in res.unknown_labs:
                    res.unknown_labs.append(item.lab_code)
                continue
            if item.timestamp > current_ms + MAX_FUTURE_SKEW_MS or item.timestamp <= 0:
                res.rejected += 1
                continue
            device = (item.device_model or "")[:120]
            key = (lab.id, item.timestamp, device)
            if key in seen or self.events.exists(*key):
                res.duplicates += 1
                continue
            seen.add(key)
            ev = self._store(lab, item.timestamp, item.count, item.entries, item.exits, item.source[:24], device)
            res.accepted += 1
            if lab.id not in latest or ev.timestamp_ms >= latest[lab.id].timestamp_ms:
                latest[lab.id] = ev

        self.db.flush()
        for lab_id, ev in latest.items():
            if current_ms - ev.timestamp_ms <= FRESH_MS:
                lab = next(lab_ for lab_ in labs.values() if lab_.id == lab_id)
                res.alerts += self._evaluate(lab, ev)
        self.db.commit()

        for ev in latest.values():
            lab = next(lab_ for lab_ in labs.values() if lab_.id == ev.lab_id)
            manager.publish({"type": "occupancy", "labCode": lab.code, "count": ev.count,
                             "timestamp": ev.timestamp_ms, "source": ev.source})
        for a in res.alerts:
            manager.publish({"type": "alert", "alert": alert_to_dict(a)})
        return res

    def record(self, lab: Lab, count: int, entries: int, exits: int, source: str, device: str,
               ts_ms: int | None = None) -> list[Alert]:
        """Registro de una sola lectura (lo usa el modulo de vision del backend)."""
        ts = ts_ms or now_ms()
        ev = self._store(lab, ts, count, entries, exits, source, device)
        self.db.flush()
        new_alerts = self._evaluate(lab, ev)
        self.db.commit()
        manager.publish({"type": "occupancy", "labCode": lab.code, "count": count, "timestamp": ts, "source": source})
        for a in new_alerts:
            manager.publish({"type": "alert", "alert": alert_to_dict(a)})
        return new_alerts

    # ------------------------------------------------------------------ internos
    def _store(self, lab: Lab, ts: int, count: int, entries: int, exits: int, source: str, device: str) -> OccupancyEvent:
        dt = local_dt(ts)
        ev = OccupancyEvent(lab_id=lab.id, timestamp_ms=ts, count=count, entries=entries, exits=exits,
                            hour_of_week=hour_of_week(dt), source=source, device=device)
        self.events.add(ev)
        # Sesion de clase (asistencia, atrasos)
        ctx = rules.schedule_context(self.schedule.for_lab(lab.id), dt)
        if ctx.current is not None:
            s = self.sessions.get_or_create(lab.id, ctx.current.id, dt.date().toordinal())
            if count > 0:
                s.first_presence_ms = s.first_presence_ms if s.first_presence_ms is not None else ts
                s.last_presence_ms = max(s.last_presence_ms or 0, ts)
            s.peak_count = max(s.peak_count, count)
            s.sum_count += count
            s.samples += 1
        return ev

    def _minutes_empty(self, lab: Lab, ev: OccupancyEvent) -> int:
        if ev.count > 0:
            return 0
        recent = self.events.since(lab.id, ev.timestamp_ms - 60 * 60 * 1000)
        last_busy = max((e.timestamp_ms for e in recent if e.count > 0 and e.timestamp_ms <= ev.timestamp_ms), default=None)
        if last_busy is None:
            first = min((e.timestamp_ms for e in recent), default=ev.timestamp_ms)
            return int((ev.timestamp_ms - first) / 60000)
        return int((ev.timestamp_ms - last_busy) / 60000)

    def _evaluate(self, lab: Lab, ev: OccupancyEvent) -> list[Alert]:
        dt = local_dt(ev.timestamp_ms)
        ctx = rules.schedule_context(self.schedule.for_lab(lab.id), dt)
        session = None
        if ctx.current is not None:
            s = self.sessions.find(ctx.current.id, dt.date().toordinal())
            session = s
        triggers = rules.evaluate(
            lab.capacity, ctx, ev.count,
            rules.SessionInfo(session.first_presence_ms) if session else None,
            self._minutes_empty(lab, ev),
        )
        history = [float(c) for c in self.events.history_for_slot(lab.id, ev.hour_of_week, ev.timestamp_ms - 60 * 60 * 1000)]
        anomaly = analytics.robust_z(history, float(ev.count))
        if anomaly.is_anomaly and ev.count > 0:
            triggers.append(rules.Trigger(
                AlertType.ANOMALIA_ML,
                f"Ocupación inusual: {ev.count} personas vs. mediana histórica de {anomaly.median:.0f} "
                f"para esta franja (z = {anomaly.z_score:.1f}).",
            ))

        created: list[Alert] = []
        for t in triggers:
            if t.type == AlertType.SALIDA_ANTICIPADA and session is not None:
                session.ended_early = True
            last = self.alerts.last_ts(lab.id, t.type.value)
            if last is not None and ev.timestamp_ms - last < ALERT_COOLDOWN_MS:
                continue
            created.append(self.alerts.add(Alert(lab_id=lab.id, type=t.type.value, message=t.message,
                                                 timestamp_ms=ev.timestamp_ms)))
        return created


def alert_to_dict(a: Alert) -> dict:
    t = AlertType(a.type) if a.type in AlertType._value2member_map_ else AlertType.ANOMALIA_ML
    return {
        "id": a.id, "labCode": a.lab.code if a.lab else "", "labName": a.lab.name if a.lab else "",
        "type": a.type, "title": ALERT_TITLES[t], "message": a.message, "timestamp": a.timestamp_ms, "read": a.read,
    }
