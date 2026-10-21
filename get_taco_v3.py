import os
import urllib.request
from ultralytics import YOLO

def download_and_export_taco():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    # TACO Modell von einem Mirror, der direkten Download erlaubt
    url = "https://github.com/mrdvince/taco-yolov8/releases/download/v1.0/best.pt"
    local_pt = "taco_best.pt"
    
    print("Lade TACO-Modell von GitHub herunter...")
    try:
        opener = urllib.request.build_opener()
        opener.addheaders = [('User-agent', 'Mozilla/5.0')]
        urllib.request.install_opener(opener)
        urllib.request.urlretrieve(url, local_pt)
        
        model = YOLO(local_pt)
        # TACO hat typischerweise 60 Klassen
        print(f"Modell geladen mit {len(model.names)} Klassen.")
        
        print("Exportiere zu ONNX (320x320)...")
        path = model.export(format="onnx", imgsz=320)
        
        target_path = os.path.join(assets_dir, "taco_model.onnx")
        if os.path.exists(path):
            if os.path.exists(target_path): os.remove(target_path)
            os.rename(path, target_path)
            print(f"ERFOLG: {target_path}")
            print("LABELS_START")
            print(list(model.names.values()))
            print("LABELS_END")
            return True
    except Exception as e:
        print(f"Fehler: {e}")
        return False

if __name__ == "__main__":
    download_and_export_taco()
