# OmniFlow · Componente de Machine Learning

Modelo de **reconocimiento facial para la asistencia automática** (rúbrica de ML del proyecto integrador).

| Criterio de la rúbrica | Dónde está |
|---|---|
| Análisis exploratorio (EDA) | Notebook, sección 2: distribución de clases, calidad de imagen, detección, separabilidad (KDE), PCA, correlaciones, atípicos · `reports/figures/eda_*.png` |
| Preprocesamiento / limpieza | Sección 3: detección y alineación, filtros de calidad, atípicos y balanceo **solo en entrenamiento** (sin fuga de datos), normalización L2 |
| Prototipo funcional que consume el modelo | `reports/face_policy.json` → `omniflow-backend/app/ml_models/face_policy.json` → `POST /api/v1/attendance/frame` (app Android: Cámara → Asistencia automática) |
| Documentación | Notebook (objetivo, metodología, resultados, conclusiones, referencias) |

## Ejecutar
```bash
python -m venv .venv && .venv\Scripts\activate
pip install scikit-learn pandas matplotlib seaborn opencv-python-headless pillow nbformat nbclient ipykernel joblib
python build_notebook.py   # genera el notebook
python run_notebook.py     # descarga LFW (~230 MB), ejecuta y guarda gráficas y face_policy.json
```
También se puede abrir `notebooks/01_reconocimiento_facial_asistencia.ipynb` en Jupyter/VS Code.

Dataset: *Labeled Faces in the Wild* (descarga automática con scikit-learn a `data/`, no se versiona).
Modelos: YuNet y SFace de OpenCV Zoo (Apache-2.0), en `omniflow-backend/app/ml_models/`.
