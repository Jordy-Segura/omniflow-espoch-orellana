"""Sincronizacion con OASIS: docentes, estudiantes y horarios de los bloques de laboratorio.

Para cada bloque del horario de laboratorio (PDF del periodo):
  1. Se identifica la materia en la malla de la carrera (GetMallaCurricularPensumVigenteSinDescripcion),
     comparando nombres normalizados (sin tildes, similitud difflib) y priorizando el nivel del bloque.
  2. Se obtiene el docente:
       a) el escrito en el PDF (teacherHint), si OASIS lo conoce;
       b) si no, el docente cuyo horario del periodo (GetHorariosDocente) incluye esa materia,
          prefiriendo el que la dicta el mismo dia y a la misma hora;
       c) si no, el docente del dictado de la materia (GetDictadosMateria).
  3. Se cargan los estudiantes de esa materia/paralelo (GetAlumnosMateria); si OASIS no devuelve
     ninguno, se usan los matriculados del nivel (GetTodasMatriculaEstudiantes) como aproximacion.
"""
from __future__ import annotations

import difflib
import json
import logging
import re
import threading
import unicodedata
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.integrations.oasis import OasisClient, OasisError
from app.models import Lab, Person, ScheduleSlot, SlotStudent, SyncRun

log = logging.getLogger(__name__)

DAY_CODES = {"LUN": 1, "MAR": 2, "MIE": 3, "MIÉ": 3, "JUE": 4, "VIE": 5, "SAB": 6, "SÁB": 6, "DOM": 7}
_STOP = {"DE", "LA", "EL", "Y", "DEL", "LOS", "LAS", "EN"}


def norm(text: str) -> str:
    t = unicodedata.normalize("NFD", (text or "").upper()).encode("ascii", "ignore").decode()
    t = re.sub(r"^ITINERARIO\s*\d+\s*:\s*", "", t)
    t = re.sub(r"[^A-Z0-9 ]+", " ", t)
    words = [w[:-1] if len(w) > 4 and w.endswith("S") else w for w in t.split()]  # BASES -> BASE
    return " ".join(w for w in words if w not in _STOP)


def similarity(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, norm(a), norm(b)).ratio()


def hm(text: str) -> int:
    h, m = (text or "0:0").split(":")[:2]
    return int(h) * 60 + int(m)


def clean_email(text: str | None) -> str:
    """OASIS a veces devuelve el correo como 'mailto:usuario@dominio'."""
    t = (text or "").strip()
    if t.lower().startswith("mailto:"):
        t = t[7:]
    return t.strip().lower()


def title(s: str) -> str:
    return " ".join(w.capitalize() for w in (s or "").split())


@dataclass
class SlotReport:
    lab: str
    day: int
    start: str
    subject: str
    career: str
    level: int
    subject_code: str = ""
    teacher: str = ""
    teacher_source: str = ""  # PDF | HORARIO | DICTADO | NINGUNO
    parallel: str = ""
    enrolled: int = 0
    students_source: str = ""  # MATERIA | NIVEL | NINGUNO
    notes: list[str] = field(default_factory=list)


@dataclass
class SyncReport:
    period: str = ""
    period_description: str = ""
    careers: list[str] = field(default_factory=list)
    teachers: int = 0
    students: int = 0
    slots: list[SlotReport] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    accounts_created: int = 0

    def summary(self) -> dict:
        by_src = defaultdict(int)
        for s in self.slots:
            by_src[s.teacher_source] += 1
        return {"period": self.period, "periodDescription": self.period_description, "careers": self.careers,
                "teachers": self.teachers, "students": self.students, "slots": len(self.slots),
                "accountsCreated": self.accounts_created,
                "teacherSources": dict(by_src), "errors": self.errors}


class OasisSync:
    def __init__(self, db: Session, client: OasisClient, workers: int = 6) -> None:
        self.db = db
        self.client = client
        self.workers = workers
        self._links: dict[int, list[str]] = {}

    def _pmap(self, fn, items):
        with ThreadPoolExecutor(self.workers) as ex:
            return list(ex.map(fn, items))

    def run(self) -> SyncReport:
        rep = SyncReport()
        periodo = self.client.get_periodo_actual() or {}
        rep.period = periodo.get("Codigo", "")
        rep.period_description = periodo.get("Descripcion", "")
        if not rep.period:
            raise OasisError("OASIS no devolvió el período actual")

        slots = list(self.db.scalars(select(ScheduleSlot).where(ScheduleSlot.career_code != "")))
        labs = {lab.id: lab.code for lab in self.db.scalars(select(Lab))}
        careers = sorted({s.career_code for s in slots})
        rep.careers = careers

        teachers: dict[str, dict] = {}       # cedula -> {nombres, apellidos, email}
        students: dict[str, dict] = {}       # cedula -> {..., level, career, codigo}

        for career in careers:
            career_slots = [s for s in slots if s.career_code == career]
            try:
                self._sync_career(career, career_slots, rep, labs, teachers, students)
            except OasisError as e:
                rep.errors.append(f"{career}: {e}")
                log.warning("Sincronización de %s falló: %s", career, e)

        self._upsert_people(teachers, "DOCENTE")
        self._upsert_people(students, "ESTUDIANTE")
        for slot_id, cedulas in self._links.items():
            self.db.execute(delete(SlotStudent).where(SlotStudent.slot_id == slot_id))
            self.db.add_all(SlotStudent(slot_id=slot_id, cedula=c) for c in cedulas)
        rep.teachers = len(teachers)
        rep.students = len(students)
        # Cuentas propias (clave inicial = cedula) para docentes y estudiantes nuevos.
        from app.services.teachers import apply_assignments
        from app.services.users import ensure_accounts
        rep.accounts_created = ensure_accounts(self.db)
        apply_assignments(self.db)
        self.db.commit()
        return rep

    # ------------------------------------------------------------------ por carrera
    def _sync_career(self, career: str, slots: list[ScheduleSlot], rep: SyncReport, labs: dict[int, str],
                     teachers: dict[str, dict], students: dict[str, dict]) -> None:
        period = rep.period
        malla = self.client.get_malla(career)

        # 1) materia de la malla para cada bloque
        subject_of: dict[int, dict] = {}
        for s in slots:
            same_level = [m for m in malla if str(m.get("CodNivel")) == str(s.level)]
            best = max(same_level or malla, key=lambda m: similarity(s.subject, m.get("Materia", "")), default=None)
            if best is not None and similarity(s.subject, best.get("Materia", "")) >= 0.72:
                subject_of[s.id] = best
            elif best is not None:
                # Ultimo intento en toda la malla (el PDF puede tener otro nivel)
                anywhere = max(malla, key=lambda m: similarity(s.subject, m.get("Materia", "")))
                if similarity(s.subject, anywhere.get("Materia", "")) >= 0.72:
                    subject_of[s.id] = anywhere

        # 2) dictados de las materias usadas -> docentes candidatos
        codes = sorted({m["CodMateria"] for m in subject_of.values()})
        dictados = dict(zip(codes, self._pmap(lambda c: self.client.get_dictados(career, c), codes)))
        for ds in dictados.values():
            for d in ds:
                doc = d.get("Docente") or {}
                if isinstance(doc, dict) and doc.get("Cedula"):
                    teachers.setdefault(doc["Cedula"], {
                        "nombres": doc.get("Nombres", "").strip(), "apellidos": doc.get("Apellidos", "").strip(),
                        "email": clean_email(doc.get("Email")), "career": career})

        # Docentes escritos en el PDF que no aparecen en los dictados: buscarlos en los dictados del resto de la malla.
        hints = {s.teacher_hint for s in slots if s.teacher_hint}
        if hints and not all(self._find_teacher(h, teachers) for h in hints):
            other = [m["CodMateria"] for m in malla if m["CodMateria"] not in dictados]
            for ds in self._pmap(lambda c: self.client.get_dictados(career, c), other):
                for d in ds:
                    doc = d.get("Docente") or {}
                    if isinstance(doc, dict) and doc.get("Cedula"):
                        teachers.setdefault(doc["Cedula"], {
                            "nombres": doc.get("Nombres", "").strip(), "apellidos": doc.get("Apellidos", "").strip(),
                            "email": clean_email(doc.get("Email")), "career": career})

        # 3) horario del periodo de cada docente -> indice materia -> [(cedula, dia, inicio, fin)]
        career_teachers = [c for c, t in teachers.items() if t.get("career") == career]
        horarios = dict(zip(career_teachers, self._pmap(
            lambda c: self.client.get_horarios_docente(career, c, period), career_teachers)))
        by_code: dict[str, list[tuple[str, int, int, int]]] = defaultdict(list)
        by_name: dict[str, list[tuple[str, int, int, int]]] = defaultdict(list)
        for ced, hs in horarios.items():
            for h in hs:
                entry = (ced, DAY_CODES.get(h.get("CodDia", "")[:3].upper(), 0), hm(h.get("Inicio")), hm(h.get("Fin")))
                by_code[h.get("CodMateria", "")].append(entry)
                by_name[norm(h.get("Materia", ""))].append(entry)

        # 4) matricula del periodo (respaldo cuando GetAlumnosMateria no devuelve nada)
        matricula = self.client.get_todas_matricula(career, period)
        by_level: dict[int, list[dict]] = defaultdict(list)
        for m in matricula:
            if m.get("Cedula"):
                by_level[int(m.get("CodNivel") or 0)].append(m)

        # 5) resolver cada bloque
        alumnos_cache: dict[tuple[str, str, str], list[dict]] = {}
        jobs = []
        for s in slots:
            r = SlotReport(lab=labs.get(s.lab_id, ""), day=s.day_of_week,
                           start=f"{s.start_minute // 60:02d}:{s.start_minute % 60:02d}",
                           subject=s.subject, career=career, level=s.level)
            materia = subject_of.get(s.id)
            if materia is None:
                r.notes.append("Materia no encontrada en la malla de OASIS")
                r.teacher_source = "NINGUNO"
                rep.slots.append(r)
                continue
            r.subject_code = materia["CodMateria"]
            ced, src = self._pick_teacher(s, materia, teachers, by_code, by_name, dictados.get(materia["CodMateria"], []), r)
            r.teacher_source = src
            par_ced = ced or self._best_candidate(s, by_code.get(materia["CodMateria"]) or [])
            parallel = self._parallel_for(par_ced, s.level, dictados.get(materia["CodMateria"], []))
            r.parallel = parallel
            s.subject_code = materia["CodMateria"]
            s.parallel = f"{s.level}°{parallel}" if parallel else s.parallel
            s.period_code = period
            if ced:
                t = teachers[ced]
                s.teacher_cedula = ced
                s.teacher_email = t.get("email", "")
                s.teacher = title(f"{t['nombres']} {t['apellidos']}")
            elif s.teacher_hint:
                s.teacher_cedula, s.teacher_email = "", ""
                s.teacher = title(s.teacher_hint)
            r.teacher = s.teacher
            jobs.append((s, r, (materia["CodMateria"], str(s.level), parallel or "1")))
            rep.slots.append(r)

        keys = sorted({k for _, _, k in jobs})

        def fetch(k):
            try:
                return k, self.client.get_alumnos_materia(career, k[1], k[2], period, k[0])
            except OasisError:
                return k, []

        alumnos_cache.update(self._pmap(fetch, keys))
        level_info = {m["Cedula"]: m for m in matricula if m.get("Cedula")}
        for s, r, k in jobs:
            alumnos = alumnos_cache.get(k) or []
            if alumnos:
                r.students_source = "MATERIA"
            else:
                alumnos = by_level.get(s.level, [])
                r.students_source = "NIVEL" if alumnos else "NINGUNO"
                if alumnos:
                    r.notes.append("OASIS no devolvió alumnos de la materia: se usan los matriculados del nivel")
            seen: list[str] = []
            for a in alumnos:
                ced = a.get("Cedula", "")
                if not ced or ced in seen:
                    continue
                seen.append(ced)
                info = level_info.get(ced, {})
                students.setdefault(ced, {
                    "nombres": a.get("Nombres", "").strip(), "apellidos": a.get("Apellidos", "").strip(),
                    "email": clean_email(a.get("Email")), "codigo": (info.get("Codigo") or "").strip(),
                    "level": int(info.get("CodNivel") or s.level or 0), "career": career})
            self._links[s.id] = seen
            s.enrolled = len(seen)
            r.enrolled = len(seen)

    # ------------------------------------------------------------------ ayudantes
    @staticmethod
    def _find_teacher(hint: str, teachers: dict[str, dict]) -> str | None:
        want = set(norm(hint).split())
        for ced, t in teachers.items():
            have = set(norm(f"{t['apellidos']} {t['nombres']}").split())
            if want and want <= have:
                return ced
        return None

    def _pick_teacher(self, s, materia, teachers, by_code, by_name, dictados, r: SlotReport) -> tuple[str | None, str]:
        candidates = by_code.get(materia["CodMateria"]) or by_name.get(norm(materia.get("Materia", ""))) or []
        if s.teacher_hint:
            ced = self._find_teacher(s.teacher_hint, teachers)
            if ced:
                others = {c for c, *_ in candidates if c != ced}
                if candidates and ced not in {c for c, *_ in candidates}:
                    names = ", ".join(title(f"{teachers[c]['nombres']} {teachers[c]['apellidos']}") for c in others)
                    r.notes.append(f"El PDF indica {title(s.teacher_hint)} pero el horario de OASIS asigna la materia a {names}")
                return ced, "PDF"
            # El PDF manda: se muestra su docente aunque OASIS no lo tenga registrado.
            names = ", ".join(sorted({title(f"{teachers[c]['nombres']} {teachers[c]['apellidos']}") for c, *_ in candidates}))
            r.notes.append(f"Docente del PDF ({title(s.teacher_hint)}) no registrado en OASIS"
                           + (f"; OASIS asigna la materia a {names}" if names else ""))
            return None, "PDF"
        if candidates:
            def score(c):
                _, day, ini, fin = c
                overlap = day == s.day_of_week and ini < s.end_minute and s.start_minute < fin
                return (overlap, day == s.day_of_week)
            best = max(candidates, key=score)
            if not score(best)[0]:
                r.notes.append("OASIS registra la materia en otro día/hora que el PDF")
            return best[0], "HORARIO"
        for d in dictados:
            doc = d.get("Docente") or {}
            if isinstance(doc, dict) and doc.get("Cedula") and str(d.get("CodNivel")) == str(s.level):
                return doc["Cedula"], "DICTADO"
        for d in dictados:
            doc = d.get("Docente") or {}
            if isinstance(doc, dict) and doc.get("Cedula"):
                return doc["Cedula"], "DICTADO"
        return None, "NINGUNO"

    @staticmethod
    def _best_candidate(s, candidates) -> str | None:
        if not candidates:
            return None
        return max(candidates, key=lambda c: (c[1] == s.day_of_week and c[2] < s.end_minute and s.start_minute < c[3],
                                               c[1] == s.day_of_week))[0]

    @staticmethod
    def _parallel_for(cedula: str | None, level: int, dictados: list[dict]) -> str:
        for d in dictados:
            doc = d.get("Docente") or {}
            if cedula and isinstance(doc, dict) and doc.get("Cedula") == cedula:
                return str(d.get("Paralelo") or "")
        same_level = [d for d in dictados if str(d.get("CodNivel")) == str(level)]
        return str((same_level or dictados or [{}])[0].get("Paralelo") or "")

    def _upsert_people(self, people: dict[str, dict], role: str) -> None:
        now = datetime.now(timezone.utc)
        for ced, p in people.items():
            row = self.db.get(Person, ced)
            if row is None:
                row = Person(cedula=ced, role=role)
                self.db.add(row)
            row.nombres = p.get("nombres", "")
            row.apellidos = p.get("apellidos", "")
            row.email = p.get("email", "") or row.email
            row.codigo = p.get("codigo", "") or row.codigo
            row.career_code = p.get("career", "")
            row.level = int(p.get("level", 0) or 0)
            row.synced_at = now
            if role == "DOCENTE":
                row.role = "DOCENTE"
        self.db.flush()


# ---------------------------------------------------------------------- ejecucion en segundo plano
_lock = threading.Lock()


def run_and_record(session_factory, run_id: int | None = None, client_factory=OasisClient) -> int:
    """Ejecuta la sincronizacion y la registra en `sync_runs`. Devuelve el id de la corrida."""
    if run_id is None:
        with session_factory() as db:
            run = SyncRun(status="RUNNING")
            db.add(run)
            db.commit()
            run_id = run.id
    if not _lock.acquire(blocking=False):
        with session_factory() as db:
            r = db.get(SyncRun, run_id)
            r.status, r.report, r.finished_at = "ERROR", json.dumps({"errors": ["Ya hay una sincronización en curso"]}), datetime.now(timezone.utc)
            db.commit()
        return run_id
    client = client_factory()
    try:
        with session_factory() as db:
            try:
                rep = OasisSync(db, client).run()
                payload = {**rep.summary(), "detail": [asdict(s) for s in rep.slots]}
                status = "OK" if not rep.errors else "ERROR"
            except Exception as e:  # noqa: BLE001
                db.rollback()
                log.exception("Sincronización OASIS falló")
                payload, status = {"errors": [str(e)]}, "ERROR"
            r = db.get(SyncRun, run_id)
            r.status, r.report, r.finished_at = status, json.dumps(payload, ensure_ascii=False), datetime.now(timezone.utc)
            db.commit()
    finally:
        client.close()
        _lock.release()
    return run_id


def start_background(session_factory, run_id: int | None = None) -> threading.Thread:
    t = threading.Thread(target=run_and_record, args=(session_factory, run_id), name="oasis-sync", daemon=True)
    t.start()
    return t
