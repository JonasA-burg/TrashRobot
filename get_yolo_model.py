import urllib.request
import os
import sys

def download_yolo():
    project_assets = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    model_name = "yolov8n_float32.tflite"
    target_path = os.path.join(project_assets, model_name)
    
    # Ein sehr stabiler Link von einem verifizierten Repository
    url = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_float32.tflite"
    
    print(f"--- YOLO Modell-Download via Python {sys.version.split()[0]} ---")
    
    if not os.path.exists(project_assets):
        print(f"Erstelle Ordner: {project_assets}")
        os.makedirs(project_assets)

    print(f"Lade Modell herunter von: {url}")
    print("Das kann einen Moment dauern (ca. 12 MB)...")
    
    try:
        # User-Agent hinzufügen, da manche Server einfache Skripte blockieren
        opener = urllib.request.build_opener()
        opener.addheaders = [('User-agent', 'Mozilla/5.0')]
        urllib.request.install_opener(opener)
        
        urllib.request.urlretrieve(url, target_path)
        
        size = os.path.getsize(target_path) / (1024 * 1024)
        if size > 5:
            print(f"ERFOLG! Modell gespeichert unter: {target_path}")
            print(f"Größe: {size:.2f} MB")
            return True
        else:
            print(f"FEHLER: Datei ist zu klein ({size:.4f} MB). Download unvollständig.")
            return False
    except Exception as e:
        print(f"FEHLER beim Download: {e}")
        return False

if __name__ == "__main__":
    if download_yolo():
        print("\nDas Modell ist jetzt in deiner App! Klicke in Android Studio auf den grünen PLAY-Button.")
    else:
        print("\nDownload fehlgeschlagen. Bitte prüfe deine Internetverbindung.")
