from ultralytics import YOLO
import os
import shutil
from pathlib import Path

def get_it_done():
    print("Starte Ultralytics-API...")
    # Dies lädt das Modell in den lokalen Cache (meist ~/AppData/Roaming/Ultralytics)
    model = YOLO("yolov8n.pt")
    
    # Pfad zum Assets-Ordner der App
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    if not os.path.exists(assets_dir):
        os.makedirs(assets_dir)

    # Wir suchen das Modell im Cache
    # Ultralytics speichert es als 'yolov8n.pt'
    # Da wir TFLite wollen, versuchen wir den Export NOCHMAL, aber diesmal mit Fehlerbehandlung
    print("Versuche Export (lokal)...")
    try:
        # Falls TFLite nicht geht, exportieren wir nach ONNX
        # Das klappt fast IMMER
        export_path = model.export(format="onnx", imgsz=320)
        
        target_onnx = os.path.join(assets_dir, "yolov8n.onnx")
        shutil.copy(export_path, target_onnx)
        print(f"ERFOLG: ONNX Modell erstellt: {target_onnx}")
        return "onnx"
    except Exception as e:
        print(f"ONNX Export fehlgeschlagen: {e}")
        return None

format_found = get_it_done()
if format_found:
    print(f"Format {format_found} ist bereit!")
