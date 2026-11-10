"""Horario del PDF + sincronizacion con OASIS (cliente OASIS simulado, sin red)."""
import json

from sqlalchemy import select

from app import database
from app.models import Person, ScheduleSlot, SlotStudent
from app.services.oasis_sync import OasisSync, norm, run_and_record
from app.services.seed import load_schedule_file, parse_group, seed
from conftest import token_for


def test_pdf_schedule_has_three_labs_and_parses_groups():
    data = load_schedule_file()
    assert [l["code"] for l in data["labs"]] == ["LAB-C1", "LAB-C2", "LAB-C3", "LAB-C4"]
    assert sum(len(l["blocks"]) for l in data["labs"]) == 57
    # Sede Orellana
    assert parse_group("TI03", data["careers"]) == ("ITIO", 3)
    assert parse_group("AMBI05", data["careers"]) == ("IAMBENA", 5)
    assert parse_group("AGRO03", data["careers"]) == ("IAGRENA", 3)
    assert parse_group("ZOOT05", data["careers"]) == ("IZOOENA", 5)
    for lab in data["labs"]:
        for b in lab["blocks"]:
            assert b["start"] < b["end"] and 1 <= b["day"] <= 5


def test_norm_matches_pdf_and_oasis_names():
    assert norm("ADMINISTRACIÓN DE BASES DE DATOS") == norm("ADMINISTRACION DE BASE DE DATOS")
    assert norm("ITINERARIO 1: CRIPTOGRAFÍA") == norm("CRIPTOGRAFIA")


MINI = {
    "period": "P0046",
    "careers": {"TI": "TI"},
    "labs": [{"code": "LAB-C1", "name": "Laboratorio de Cómputo 1", "capacity": 30, "blocks": [
        {"day": 2, "start": "10:00", "end": "12:00", "subject": "MACHINE LEARNING", "group": "TI06"},
        {"day": 3, "start": "10:00", "end": "12:00", "subject": "FUNDAMENTOS DE BASE DE DATOS", "group": "TI03"},
        {"day": 2, "start": "15:00", "end": "17:00", "subject": "FUNDAMENTOS DE BASE DE DATOS", "group": "TI03"},
        {"day": 4, "start": "13:00", "end": "15:00", "subject": "INTEROPERABILIDAD DE PLATAFORMAS", "group": "TI06",
         "teacherHint": "DOCENTE DEL PDF"},
        {"day": 5, "start": "09:00", "end": "11:00", "subject": "MATERIA INEXISTENTE", "group": "TI02"},
    ]}],
}


def doc(ced, ap, no):
    return {"Cedula": ced, "Apellidos": ap, "Nombres": no, "Email": f"{no.split()[0].lower()}@espoch.edu.ec"}


class FakeOasis:
    calls = 0

    def get_periodo_actual(self):
        return {"Codigo": "P0046", "Descripcion": "7 SEPTIEMBRE 2026 - 5 FEBRERO 2027"}

    def get_malla(self, career):
        return [
            {"CodMateria": "ML", "Materia": "MACHINE LEARNING", "CodNivel": "6"},
            {"CodMateria": "FBD", "Materia": "FUNDAMENTOS DE BASE DE DATOS", "CodNivel": "3"},
            {"CodMateria": "IOP", "Materia": "INTEROPERABILIDAD DE PLATAFORMAS", "CodNivel": "6"},
        ]

    def get_dictados(self, career, code):
        return {
            "ML": [{"CodNivel": "6", "Paralelo": "1", "Docente": doc("111111111-1", "CAMACHO CASTILLO", "JAIME DAVID")}],
            "FBD": [{"CodNivel": "3", "Paralelo": "1", "Docente": doc("222222222-2", "PAGUAY ALVARADO", "MARIO ALEJANDRO")},
                    {"CodNivel": "3", "Paralelo": "2", "Docente": doc("333333333-3", "BASTIDAS GUACHO", "GISEL KATERINE")}],
            "IOP": [{"CodNivel": "6", "Paralelo": "1", "Docente": doc("444444444-4", "GAVILANEZ ALVAREZ", "OSCAR DANILO")}],
        }.get(code, [])

    def get_horarios_docente(self, career, ced, period):
        return {
            "111111111-1": [{"CodMateria": "ML", "Materia": "MACHINE LEARNING", "CodDia": "LUN", "Inicio": "7:00", "Fin": "8:00"}],
            "222222222-2": [{"CodMateria": "FBD", "Materia": "FUNDAMENTOS DE BASE DE DATOS", "CodDia": "MAR", "Inicio": "15:00", "Fin": "16:00"}],
            "333333333-3": [{"CodMateria": "FBD", "Materia": "FUNDAMENTOS DE BASE DE DATOS", "CodDia": "MIE", "Inicio": "10:00", "Fin": "11:00"}],
            "444444444-4": [{"CodMateria": "IOP", "Materia": "INTEROPERABILIDAD DE PLATAFORMAS", "CodDia": "JUE", "Inicio": "13:00", "Fin": "14:00"}],
        }.get(ced, [])

    def get_todas_matricula(self, career, period):
        return [{"Cedula": f"9000000{i:02d}-0", "Nombres": f"EST {i}", "Apellidos": "PRUEBA", "Codigo": f"C{i}",
                 "CodNivel": "6" if i < 5 else "3"} for i in range(10)]

    def get_alumnos_materia(self, career, nivel, paralelo, period, code):
        if code == "IOP":
            return []  # fuerza el respaldo por nivel
        n = {"ML": 3, "FBD": 2 if paralelo == "2" else 4}[code]
        return [{"Cedula": f"8000{code}{paralelo}{i}-0", "Nombres": f"ALUMNO {i}", "Apellidos": code} for i in range(n)]

    def close(self):
        pass


def test_sync_resolves_teachers_parallels_and_students(client):
    with database.SessionLocal() as db:
        seed(db, data=MINI)
        rep = OasisSync(db, FakeOasis(), workers=2).run()
    assert rep.period == "P0046" and not rep.errors
    by = {(r.day, r.subject): r for r in rep.slots}

    ml = by[(2, "MACHINE LEARNING")]
    assert ml.teacher == "Jaime David Camacho Castillo" and ml.teacher_source == "HORARIO" and ml.enrolled == 3
    assert any("otro día/hora" in n for n in ml.notes)

    # Dos paralelos de la misma materia: se elige el docente que la dicta ese dia y hora.
    fbd_mie = by[(3, "FUNDAMENTOS DE BASE DE DATOS")]
    assert fbd_mie.teacher == "Gisel Katerine Bastidas Guacho" and fbd_mie.parallel == "2" and fbd_mie.enrolled == 2
    fbd_mar = by[(2, "FUNDAMENTOS DE BASE DE DATOS")]
    assert fbd_mar.teacher == "Mario Alejandro Paguay Alvarado" and fbd_mar.parallel == "1" and fbd_mar.enrolled == 4

    # El docente del PDF manda aunque OASIS no lo tenga; alumnos por nivel si la materia no devuelve.
    iop = by[(4, "INTEROPERABILIDAD DE PLATAFORMAS")]
    assert iop.teacher == "Docente Del Pdf" and iop.teacher_source == "PDF"
    assert iop.students_source == "NIVEL" and iop.enrolled == 5

    none = by[(5, "MATERIA INEXISTENTE")]
    assert none.teacher_source == "NINGUNO"

    with database.SessionLocal() as db:
        slot = db.scalar(select(ScheduleSlot).where(ScheduleSlot.subject == "MACHINE LEARNING"))
        assert slot.teacher_cedula == "111111111-1" and slot.subject_code == "ML" and slot.parallel == "6°1"
        assert db.scalar(select(Person).where(Person.cedula == "111111111-1")).role == "DOCENTE"
        assert len(list(db.scalars(select(SlotStudent).where(SlotStudent.slot_id == slot.id)))) == 3


def test_schedule_endpoints_and_sync_run(client):
    with database.SessionLocal() as db:
        seed(db, data=MINI)
    run_id = run_and_record(database.SessionLocal, client_factory=FakeOasis)
    staff = token_for(client, "DOCENTE")
    admin = token_for(client, "ADMINISTRADOR")

    sched = client.get("/api/v1/schedule").json()  # publico, lo usa la app
    ml = next(s for s in sched if s["subject"] == "MACHINE LEARNING")
    assert ml["labCode"] == "LAB-C1" and ml["teacher"] == "Jaime David Camacho Castillo" and ml["enrolled"] == 3
    assert ml["careerCode"] == "TI" and ml["level"] == 6 and "cedula" not in json.dumps(ml).lower()

    assert client.get(f"/api/v1/schedule/{ml['id']}/students").status_code == 401
    studs = client.get(f"/api/v1/schedule/{ml['id']}/students", headers=staff).json()
    assert len(studs) == 3 and studs[0]["role"] == "ESTUDIANTE"

    assert client.get("/api/v1/people?role=DOCENTE", headers=staff).status_code == 403
    docentes = client.get("/api/v1/people?role=DOCENTE", headers=admin).json()
    assert {d["apellidos"] for d in docentes} >= {"CAMACHO CASTILLO", "BASTIDAS GUACHO"}

    last = client.get("/api/v1/admin/oasis-sync", headers=admin).json()
    assert last["id"] == run_id and last["status"] == "OK" and last["report"]["slots"] == 5
