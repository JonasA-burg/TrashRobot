import requests
import os

def download_huggingface():
    # Dieser Link ist stabil und von mir geprüft
    url = "https://huggingface.co/farley-cl/yolov8n_float32.tflite/resolve/main/yolov8n_float32.tflite"
    target = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets\yolov8n_float32.tflite"
    
    print(f"Lade Modell von HuggingFace...")
    try:
        r = requests.get(url, stream=True, timeout=60)
        r.raise_for_status()
        with open(target, 'wb') as f:
            for chunk in r.iter_content(chunk_size=8192):
                f.write(chunk)
        size = os.path.getsize(target) / (1024 * 1024)
        print(f"ERFOLG! Modell ist da: {size:.2f} MB")
        return True
    except Exception as e:
        print(f"Fehler: {e}")
        return False

download_huggingface()
