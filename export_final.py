from ultralytics import YOLO
import os
import shutil

def export_model():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    
    # 1. Lade das Basis-Modell (die .pt Datei wurde vorhin schon geladen)
    print("Lade Basis-Modell...")
    model = YOLO("yolov8n.pt")
    
    # 2. Exportiere nach TFLite
    print("Exportiere zu TFLite (das kann 1-2 Minuten dauern)...")
    # imgsz=320 spart Speicher und ist schneller auf Handys
    export_path = model.export(format="tflite", imgsz=320) 
    
    # 3. Datei an den richtigen Ort verschieben
    # Ultralytics erstellt meist einen Ordner wie 'yolov8n_saved_model'
    target_file = os.path.join(assets_dir, "yolov8n_float32.tflite")
    
    # Wir suchen die erzeugte .tflite Datei
    if os.path.exists(export_path):
        if os.path.isdir(export_path):
            for root, dirs, files in os.walk(export_path):
                for f in files:
                    if f.endswith(".tflite"):
                        shutil.copy(os.path.join(root, f), target_file)
                        print(f"ERFOLG: Modell exportiert nach {target_file}")
                        return
        else:
            shutil.copy(export_path, target_file)
            print(f"ERFOLG: Modell exportiert nach {target_file}")

if __name__ == "__main__":
    export_model()
