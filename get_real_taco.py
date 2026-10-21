import os
import urllib.request
from ultralytics import YOLO

def download_and_export_taco():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    
    # Pfad zum spezialisierten TACO-Modell (YOLOv8n)
    # Wir nutzen ein verifiziertes Modell von HuggingFace
    url = "https://huggingface.co/bdsaglam/yolov8n-taco/resolve/main/weights/best.pt"
    local_pt = "taco_best.pt"
    
    print("Lade echtes TACO-Modell herunter (ca. 6MB)...")
    try:
        urllib.request.urlretrieve(url, local_pt)
        
        model = YOLO(local_pt)
        print("Exportiere TACO-Modell zu ONNX (320x320)...")
        path = model.export(format="onnx", imgsz=320)
        
        target_path = os.path.join(assets_dir, "taco_model.onnx")
        if os.path.exists(path):
            if os.path.exists(target_path): os.remove(target_path)
            os.rename(path, target_path)
            print(f"ERFOLG: TACO-Modell bereit unter {target_path}")
            
            # Klassen ausgeben, damit wir sie in Kotlin übernehmen können
            print("TACO_LABELS:")
            labels = list(model.names.values())
            print(labels)
            
            # Aufräumen
            if os.path.exists(local_pt): os.remove(local_pt)
            return True
    except Exception as e:
        print(f"Fehler: {e}")
        return False

if __name__ == "__main__":
    download_and_export_taco()
