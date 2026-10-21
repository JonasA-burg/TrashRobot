import os
import requests
from ultralytics import YOLO

def get_trash_model():
    # Dieses Modell ist auf Müll spezialisiert (Trash/Waste Detection)
    # Wir nutzen einen Link, der oft als Fallback funktioniert
    url = "https://github.com/roboflow/notebooks/releases/download/v1.0/yolov8n-trash.pt"
    local_pt = "trash.pt"
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"

    print("Lade spezialisiertes Müll-Modell herunter...")
    try:
        response = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, stream=True)
        if response.status_code == 200:
            with open(local_pt, 'wb') as f:
                f.write(response.content)
            
            model = YOLO(local_pt)
            print("Exportiere zu ONNX...")
            path = model.export(format="onnx", imgsz=320)
            
            target_path = os.path.join(assets_dir, "taco_model.onnx")
            if os.path.exists(path):
                if os.path.exists(target_path): os.remove(target_path)
                os.rename(path, target_path)
                print(f"ERFOLG: {target_path}")
                print("LABELS:")
                print(list(model.names.values()))
                return True
        else:
            print(f"Download fehlgeschlagen: {response.status_code}")
    except Exception as e:
        print(f"Fehler: {e}")
    return False

if __name__ == "__main__":
    get_trash_model()
