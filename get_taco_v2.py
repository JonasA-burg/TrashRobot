from ultralytics import YOLO
import os

def export_taco():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    # Wir nutzen ein Modell von keremberke (sehr verbreitet für TACO/YOLOv8)
    # YOLO() lädt automatisch von HuggingFace/Ultralytics Hub herunter
    model_name = "keremberke/yolov8n-taco"
    
    print(f"Lade TACO-Modell {model_name}...")
    try:
        model = YOLO(model_name)
        print("Exportiere zu ONNX...")
        # imgsz=320 wie besprochen
        path = model.export(format="onnx", imgsz=320)
        
        target_path = os.path.join(assets_dir, "taco_model.onnx")
        if os.path.exists(path):
            if os.path.exists(target_path):
                os.remove(target_path)
            os.rename(path, target_path)
            print(f"ERFOLG: Modell unter {target_path} gespeichert.")
            
            # Klassen extrahieren für den Android Code
            print("LABELS:")
            print(list(model.names.values()))
        else:
            print("Export-Pfad nicht gefunden.")
    except Exception as e:
        print(f"Fehler: {e}")

if __name__ == "__main__":
    export_taco()
