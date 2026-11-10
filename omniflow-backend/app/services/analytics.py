"""Analitica / ML clasico: pronostico de ocupacion y deteccion de anomalias.

Mismos algoritmos que la app Android (`OccupancyForecaster.kt`, `AnomalyDetector.kt`) para que
los resultados del telefono y del servidor sean comparables.
"""
from __future__ import annotations

import math
import statistics
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class LinearModel:
    intercept: float
    slope: float
    r2: float

    def predict(self, x: float) -> float:
        return self.intercept + self.slope * x


def fit_linear(xs: list[float], ys: list[float]) -> LinearModel | None:
    """Regresion lineal por minimos cuadrados ordinarios (OLS)."""
    n = len(xs)
    if n < 2 or len(ys) != n:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return LinearModel(my, 0.0, 0.0)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    slope = sxy / sxx
    intercept = my - slope * mx
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys)
    r2 = 1.0 if ss_tot == 0 else 1.0 - ss_res / ss_tot
    return LinearModel(intercept, slope, r2)


def holt(ys: list[float], steps: float, alpha: float = 0.5, beta: float = 0.3) -> float:
    """Suavizado exponencial doble de Holt (nivel + tendencia)."""
    if not ys:
        return 0.0
    if len(ys) == 1:
        return ys[0]
    level, trend = ys[0], ys[1] - ys[0]
    for y in ys[1:]:
        prev = level
        level = alpha * y + (1 - alpha) * (level + trend)
        trend = beta * (level - prev) + (1 - beta) * trend
    return level + steps * trend


@dataclass(frozen=True)
class Forecast:
    horizon_minutes: int
    predicted: int
    trend_per_hour: float
    r2: float
    samples: int


def forecast(samples: list[tuple[int, int]], horizon_minutes: int, capacity: int) -> Forecast | None:
    """samples: (timestamp_ms, personas) ordenados. Promedio de OLS y Holt, acotado a [0, 1.5*capacidad]."""
    if len(samples) < 4:
        return None
    t0 = samples[0][0]
    xs = [(t - t0) / 60000.0 for t, _ in samples]
    ys = [float(c) for _, c in samples]
    lin = fit_linear(xs, ys)
    if lin is None:
        return None
    p_lin = lin.predict(xs[-1] + horizon_minutes)
    step = max((xs[-1] - xs[0]) / (len(xs) - 1), 0.1)
    p_holt = holt(ys, horizon_minutes / step)
    predicted = round(min(max((p_lin + p_holt) / 2, 0.0), capacity * 1.5))
    return Forecast(horizon_minutes, predicted, lin.slope * 60, min(max(lin.r2, 0.0), 1.0), len(samples))


@dataclass(frozen=True)
class AnomalyResult:
    is_anomaly: bool
    z_score: float
    median: float
    samples: int


def robust_z(history: list[float], value: float, threshold: float = 3.5, min_samples: int = 12) -> AnomalyResult:
    """z-score robusto con mediana y MAD (Iglewicz & Hoaglin)."""
    if len(history) < min_samples:
        return AnomalyResult(False, 0.0, statistics.median(history) if history else 0.0, len(history))
    med = statistics.median(history)
    mad = statistics.median([abs(h - med) for h in history])
    if mad > 1e-9:
        z = 0.6745 * (value - med) / mad
    else:
        sd = statistics.pstdev(history)
        if sd > 1e-9:
            z = (value - statistics.fmean(history)) / sd
        else:
            z = math.copysign(4.0, value - med) if abs(value - med) >= 3 else 0.0
    return AnomalyResult(abs(z) > threshold, z, med, len(history))


class MedianSmoother:
    """Mediana movil para eliminar parpadeos del detector."""

    def __init__(self, window: int = 15) -> None:
        self._values: deque[int] = deque(maxlen=window)

    def add(self, v: int) -> int:
        self._values.append(v)
        return self.value()

    def value(self) -> int:
        if not self._values:
            return 0
        s = sorted(self._values)
        return s[len(s) // 2]

    def clear(self) -> None:
        self._values.clear()
