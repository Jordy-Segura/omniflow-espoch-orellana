"""Acceso a datos. Cada repositorio recibe la sesion SQLAlchemy de la peticion."""
from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import Alert, AuditLog, Camera, ClassSession, Lab, OccupancyEvent, ScheduleSlot


class LabRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def list(self, only_active: bool = True) -> list[Lab]:
        q = select(Lab).order_by(Lab.name)
        if only_active:
            q = q.where(Lab.active.is_(True))
        return list(self.db.scalars(q))

    def by_code(self, code: str) -> Lab | None:
        return self.db.scalar(select(Lab).where(Lab.code == code))

    def by_codes(self, codes: set[str]) -> dict[str, Lab]:
        if not codes:
            return {}
        return {lab.code: lab for lab in self.db.scalars(select(Lab).where(Lab.code.in_(codes)))}

    def add(self, lab: Lab) -> Lab:
        self.db.add(lab)
        self.db.flush()
        return lab

    def count(self) -> int:
        return self.db.scalar(select(func.count(Lab.id))) or 0


class ScheduleRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def for_lab(self, lab_id: int) -> list[ScheduleSlot]:
        return list(self.db.scalars(
            select(ScheduleSlot).where(ScheduleSlot.lab_id == lab_id)
            .order_by(ScheduleSlot.day_of_week, ScheduleSlot.start_minute)
        ))

    def all(self) -> list[ScheduleSlot]:
        return list(self.db.scalars(select(ScheduleSlot)))

    def add(self, slot: ScheduleSlot) -> ScheduleSlot:
        self.db.add(slot)
        self.db.flush()
        return slot

    def get(self, slot_id: int) -> ScheduleSlot | None:
        return self.db.get(ScheduleSlot, slot_id)


class OccupancyRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def exists(self, lab_id: int, ts: int, device: str) -> bool:
        return self.db.scalar(select(OccupancyEvent.id).where(
            OccupancyEvent.lab_id == lab_id, OccupancyEvent.timestamp_ms == ts, OccupancyEvent.device == device,
        )) is not None

    def add(self, ev: OccupancyEvent) -> None:
        self.db.add(ev)

    def latest_per_lab(self) -> dict[int, OccupancyEvent]:
        sub = (select(OccupancyEvent.lab_id, func.max(OccupancyEvent.timestamp_ms).label("ts"))
               .where(OccupancyEvent.source != "DEMO")
               .group_by(OccupancyEvent.lab_id).subquery())
        q = select(OccupancyEvent).join(
            sub, (OccupancyEvent.lab_id == sub.c.lab_id) & (OccupancyEvent.timestamp_ms == sub.c.ts))
        return {e.lab_id: e for e in self.db.scalars(q)}

    def since(self, lab_id: int, since_ms: int) -> list[OccupancyEvent]:
        return list(self.db.scalars(
            select(OccupancyEvent).where(OccupancyEvent.lab_id == lab_id, OccupancyEvent.timestamp_ms >= since_ms)
            .order_by(OccupancyEvent.timestamp_ms)
        ))

    def history_for_slot(self, lab_id: int, hour_of_week: int, before_ms: int, limit: int = 500) -> list[int]:
        return list(self.db.scalars(
            select(OccupancyEvent.count).where(
                OccupancyEvent.lab_id == lab_id, OccupancyEvent.hour_of_week == hour_of_week,
                OccupancyEvent.timestamp_ms < before_ms,
            ).order_by(OccupancyEvent.timestamp_ms.desc()).limit(limit)
        ))

    def all_since(self, since_ms: int) -> list[OccupancyEvent]:
        return list(self.db.scalars(
            select(OccupancyEvent).where(OccupancyEvent.timestamp_ms >= since_ms).order_by(OccupancyEvent.timestamp_ms)
        ))


class SessionRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def get_or_create(self, lab_id: int, slot_id: int, epoch_day: int) -> ClassSession:
        s = self.db.scalar(select(ClassSession).where(ClassSession.slot_id == slot_id, ClassSession.epoch_day == epoch_day))
        if s is None:
            s = ClassSession(lab_id=lab_id, slot_id=slot_id, epoch_day=epoch_day)
            self.db.add(s)
            self.db.flush()
        return s

    def find(self, slot_id: int, epoch_day: int) -> ClassSession | None:
        return self.db.scalar(select(ClassSession).where(ClassSession.slot_id == slot_id, ClassSession.epoch_day == epoch_day))

    def from_day(self, epoch_day: int) -> list[ClassSession]:
        return list(self.db.scalars(select(ClassSession).where(ClassSession.epoch_day >= epoch_day)))


class AlertRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def last_ts(self, lab_id: int, type_: str) -> int | None:
        return self.db.scalar(select(func.max(Alert.timestamp_ms)).where(Alert.lab_id == lab_id, Alert.type == type_))

    def add(self, alert: Alert) -> Alert:
        self.db.add(alert)
        self.db.flush()
        return alert

    def list(self, limit: int = 100, unread_only: bool = False, lab_id: int | None = None) -> list[Alert]:
        q = select(Alert).order_by(Alert.timestamp_ms.desc()).limit(limit)
        if unread_only:
            q = q.where(Alert.read.is_(False))
        if lab_id is not None:
            q = q.where(Alert.lab_id == lab_id)
        return list(self.db.scalars(q))

    def get(self, alert_id: int) -> Alert | None:
        return self.db.get(Alert, alert_id)

    def count_since(self, since_ms: int) -> int:
        return self.db.scalar(select(func.count(Alert.id)).where(Alert.timestamp_ms >= since_ms)) or 0


class CameraRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def list(self) -> list[Camera]:
        return list(self.db.scalars(select(Camera).order_by(Camera.id)))

    def get(self, cam_id: int) -> Camera | None:
        return self.db.get(Camera, cam_id)

    def add(self, cam: Camera) -> Camera:
        self.db.add(cam)
        self.db.flush()
        return cam

    def delete(self, cam: Camera) -> None:
        self.db.delete(cam)


def audit(db: Session, username: str, action: str, detail: str = "") -> None:
    db.add(AuditLog(username=username, action=action, detail=detail[:2000]))
