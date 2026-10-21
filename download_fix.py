import urllib.request
import os

def download_yolo():
    # Zielpfad im Android-Projekt
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    model_name = "yolov8n_float32.tflite"
    target_path = os.path.join(assets_dir, model_name)
    
    # Korrekter, direkter Link zum Google Cloud Storage Mirror oder GitHub Assets
    # Wir nutzen hier den offiziellen Ultralytics-Asset-Link (v0.0.0 ist deren Standard-Tag fuer statische Files)
    url = "https://github.com/ultralytics/assets/releases/download/v0.0.0/yolov8n_float32.tflite"
    
    if not os.path.exists(assets_dir):
        os.makedirs(assets_dir)
        print(f"Ordner erstellt: {assets_dir}")

    print(f"Lade Modell herunter...")
    
    try:
        # User-Agent setzen, um 403-Fehler zu vermeiden
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response, open(target_path, 'wb') as out_file:
            data = response.read()
            out_file.write(data)
        
        size = os.path.getsize(target_path) / (1024 * 1024)
        if size > 10:
            print(f"ERFOLG: {model_name} wurde heruntergeladen ({size:.2f} MB).")
            print(f"Speicherort: {target_path}")
            return True
        else:
            print(f"Warnung: Datei ist verdächtig klein ({size:.2f} MB). Prüfe Link...")
            return False
    except Exception as e:
        print(f"Fehler: {e}")
        return False

if __name__ == "__main__":
    download_yolo()
