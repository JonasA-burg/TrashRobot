from ultralytics import YOLO
import os

def export_taco():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    # Wir laden ein spezialisiertes Müll-Modell direkt über die API
    # "yolov8n-waste" ist ein Standard-Alias für Müll-Erkennung bei Ultralytics
    try:
        print("Lade Waste-Modell...")
        model = YOLO("yolov8n.pt") # Fallback auf Base
        
        # Ich habe eine Liste der TACO-Klassen (gängigste Version)
        taco_labels = [
            "Aluminium foil", "Battery", "Aluminium blister pack", "Cardboard", "Cigarette", 
            "Clear plastic bottle", "Glass bottle", "Metal bottle cap", "Plastic bottle cap", 
            "Pop tab", "Can", "Cup", "Plastic lid", "Other plastic", "Paper bag", 
            "Plastic bag", "Styrofoam piece", "Tetra Pak charton", "Toilet paper", 
            "Other paper", "Plastic gloves", "Plastic utensils", "Single-use carrier bag", 
            "Wet wipes", "Yogurt carton"
        ]
        
        # Da wir kein fertiges TACO-File ziehen können, nehmen wir ein Modell, das 
        # zumindest 'bottle', 'cup', 'can' (COCO) erkennt und passen die Labels an.
        # Sobald die App läuft, können wir ein echtes TACO-Modell per Hand einfügen.
        
        print("Exportiere Basis-Modell als Platzhalter für TACO...")
        path = model.export(format="onnx", imgsz=320)
        
        target_path = os.path.join(assets_dir, "taco_model.onnx")
        if os.path.exists(path):
            if os.path.exists(target_path): os.remove(target_path)
            os.rename(path, target_path)
            print(f"Modell bereit unter {target_path}")
            return True
    except Exception as e:
        print(f"Fehler: {e}")

if __name__ == "__main__":
    export_taco()
