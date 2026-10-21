import requests
import os

def download():
    # Der ultimative, offizielle Link für die exportierten Modelle
    url = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_full_integer_quant.tflite"
    # Falls wir Float32 wollen (bessere Genauigkeit, etwas langsamer)
    url_float = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_float32.tflite"
    
    target = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets\yolov8n_float32.tflite"
    
    print(f"Lade Float32 Modell...")
    try:
        # Wir nutzen stream=True um auch große Dateien sicher zu laden
        r = requests.get(url_float, allow_redirects=True)
        if r.status_code == 200:
            with open(target, 'wb') as f:
                f.write(r.content)
            print(f"ERFOLG! Datei gespeichert: {os.path.getsize(target)/1024/1024:.2f} MB")
        else:
            print(f"Fehler: Status {r.status_code}")
    except Exception as e:
        print(f"Fehler: {e}")

download()
