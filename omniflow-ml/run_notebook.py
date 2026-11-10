"""Ejecuta el notebook y guarda las salidas (graficas incluidas) dentro del mismo archivo."""
import sys
from pathlib import Path

import nbformat
from nbclient import NotebookClient

path = Path(__file__).parent / "notebooks" / "01_reconocimiento_facial_asistencia.ipynb"
nb = nbformat.read(path, as_version=4)
client = NotebookClient(nb, timeout=3600, kernel_name="python3", resources={"metadata": {"path": str(path.parent)}})
try:
    client.execute()
finally:
    nbformat.write(nb, path)
print("OK:", path)
sys.exit(0)
