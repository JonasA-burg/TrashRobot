import os
import requests
from ultralytics import YOLO

def download_taco_v8():
    # Wir nehmen ein sehr gut bewertetes TACO Modell
    url = "https://huggingface.co/keremberke/yolov8n-taco/resolve/main/best.pt"
    local_pt = "taco_best.pt"
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"

    print("Starte Download des TACO-Modells...")
    try:
        # Manche Server brauchen einen User-Agent
        headers = {'User-Agent': 'Mozilla/5.0'}
        r = requests.get(url, headers=headers, allow_redirects=True)
        with open(local_pt, 'wb') as f:
            f.write(r.content)
        
        print("Exportiere zu ONNX (320x320)...")
        model = YOLO(local_pt)
        path = model.export(format="onnx", imgsz=320)
        
        target_path = os.path.join(assets_dir, "taco_model.onnx")
        if os.path.exists(path):
            if os.path.exists(target_path): os.remove(target_path)
            os.rename(path, target_path)
            print(f"ERFOLG: Modell in Assets gespeichert.")
            print("CLASS_NAMES:")
            print(list(model.names.values()))
            return True
    except Exception as e:
        print(f"Fehler: {e}")
    return False

if __name__ == "__main__":
    download_taco_v8()
