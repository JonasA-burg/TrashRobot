import urllib.request
import os

def final_down():
    # Link von einem öffentlichen Projekt-Mirror
    url = "https://github.com/mikel-brostrom/yolo_tracking/releases/download/v10.0.45/yolov8n_float32.tflite"
    target = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets\yolov8n_float32.tflite"
    
    print(f"Lade Modell von Tracking-Repo Mirror...")
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response, open(target, 'wb') as out_file:
            out_file.write(response.read())
        print(f"GESCHAFFT! Größe: {os.path.getsize(target)/1024/1024:.2f} MB")
    except Exception as e:
        print(f"Fehler: {e}")

final_down()
