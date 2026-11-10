"""Mismos casos que AnalyticsAndRulesTest.kt de la app: ambos lados deben comportarse igual."""
from dataclasses import dataclass
from datetime import datetime

from app.services import analytics, rules
from app.services.rules import AlertType, LabStatus


@dataclass
class Slot:
    id: int = 7
    subject: str = "Programación Avanzada"
    teacher: str = "Ing. Carlos García"
    day_of_week: int = 1
    start_minute: int = 10 * 60
    end_minute: int = 12 * 60


SLOT = Slot()


def at(h, m):
    return datetime(2026, 11, 9, h, m)  # lunes


def test_linear_regression():
    m = analytics.fit_linear([float(i) for i in range(10)], [3.0 + 2.0 * i for i in range(10)])
    assert abs(m.slope - 2) < 1e-9 and abs(m.intercept - 3) < 1e-9 and abs(m.r2 - 1) < 1e-9


def test_forecast_follows_trend():
    samples = [(1_000_000 + i * 60_000, 5 + i) for i in range(10)]
    f = analytics.forecast(samples, 5, 40)
    assert 18 <= f.predicted <= 21
    assert abs(f.trend_per_hour - 60) < 1e-6
    assert analytics.forecast(samples[:3], 5, 40) is None


def test_robust_anomaly():
    hist = [float(x) for x in [20, 22, 19, 21, 23, 20, 18, 22, 21, 20, 19, 21, 22, 20]]
    assert not analytics.robust_z(hist, 21).is_anomaly
    assert analytics.robust_z(hist, 45).is_anomaly
    assert not analytics.robust_z(hist[:5], 45).is_anomaly


def test_median_smoother():
    s = analytics.MedianSmoother(5)
    for v in [10, 10, 0, 10, 10]:
        s.add(v)
    assert s.value() == 10


def test_status():
    during = rules.schedule_context([SLOT], at(10, 30))
    assert rules.lab_status(30, during, 12) == LabStatus.OCUPADO
    assert rules.lab_status(30, during, 35) == LabStatus.ANOMALO
    assert rules.lab_status(30, rules.schedule_context([SLOT], at(9, 40)), 0) == LabStatus.PROXIMO
    assert rules.lab_status(30, rules.schedule_context([SLOT], at(8, 0)), 0) == LabStatus.LIBRE
    night = rules.schedule_context([SLOT], at(22, 0))
    assert rules.lab_status(30, night, 0) == LabStatus.FUERA_DE_HORARIO
    assert rules.lab_status(30, night, 3) == LabStatus.ANOMALO


def test_rules():
    types = lambda ts: [t.type for t in ts]  # noqa: E731
    assert types(rules.evaluate(30, rules.schedule_context([SLOT], at(15, 0)), 4, None, 0)) == [AlertType.FUERA_DE_HORARIO]
    during = rules.schedule_context([SLOT], at(10, 20))
    assert AlertType.SOBREAFORO in types(rules.evaluate(30, during, 31, None, 0))
    assert types(rules.evaluate(30, during, 0, None, 0)) == [AlertType.AUSENCIA_DOCENTE]
    present = rules.SessionInfo(first_presence_ms=1)
    left = rules.schedule_context([SLOT], at(11, 0))
    assert types(rules.evaluate(30, left, 0, present, 6)) == [AlertType.SALIDA_ANTICIPADA]
    assert rules.evaluate(30, left, 18, present, 0) == []


def test_role_mapping():
    assert rules.map_oasis_role(["DOCENTE"]) == "DOCENTE"
    assert rules.map_oasis_role(["ESTUDIANTE"]) == "ESTUDIANTE"
    assert rules.map_oasis_role(["DIRECTOR DE CARRERA", "DOCENTE"]) == "COORDINADOR"
