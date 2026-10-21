from ultralytics import YOLO
import os
import shutil

def main():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    if not os.path.exists(assets_dir):
        os.makedirs(assets_dir)
        
    print("Starte offiziellen Ultralytics-Download...")
    # Das lädt die .pt Datei (ca. 6MB)
    model = YOLO("yolov8n.pt")
    
    # Da TFLite Export in Python 3.14 schwierig ist, 
    # laden wir eine alternative Version oder nutzen die .pt falls die App sie kann.
    # Da die App aber .tflite erwartet, versuchen wir den direkten Download des Assets via API
    from ultralytics.utils.downloads import safe_download
    
    url = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_float32.tflite"
    print(f"Versuche safe_download von: {url}")
    
    try:
        safe_download(url, file="yolov8n_float32.tflite", dir=assets_dir)
        print("DOWNLOAD ERFOLGREICH!")
    except Exception as e:
        print(f"Fehler: {e}")

if __name__ == "__main__":
    main()
