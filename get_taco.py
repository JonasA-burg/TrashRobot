import urllib.request
import os

def download_taco():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    # Ein von der Community trainiertes YOLOv8n TACO-Modell (ONNX Format)
    # Quelle: Ein verifizierter Mirror für TACO-Modelle
    url = "https://huggingface.co/keremberke/yolov8n-taco/resolve/main/onnx/model.onnx"
    target_path = os.path.join(assets_dir, "taco_model.onnx")
    
    print(f"Lade TACO-Modell herunter...")
    
    try:
        # User-Agent setzen
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response, open(target_path, 'wb') as out_file:
            out_file.write(response.read())
        
        size = os.path.getsize(target_path) / (1024 * 1024)
        print(f"ERFOLG: TACO-Modell bereit ({size:.2f} MB)")
        return True
    except Exception as e:
        print(f"Fehler beim Download: {e}")
        return False

if __name__ == "__main__":
    download_taco()
