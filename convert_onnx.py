import os
import subprocess

def convert():
    onnx_file = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets\yolov8n.onnx"
    target_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    
    print("Wandle ONNX in TFLite um (mit onnx2tf)...")
    try:
        # onnx2tf ist ein Kommandozeilen-Tool
        subprocess.run(["onnx2tf", "-i", onnx_file, "-o", target_dir], check=True)
        
        # Wir suchen die erzeugte Datei (sie heißt oft model_float32.tflite)
        # und benennen sie um in yolov8n_float32.tflite
        for f in os.listdir(target_dir):
            if f.endswith(".tflite") and "float32" in f:
                os.rename(os.path.join(target_dir, f), os.path.join(target_dir, "yolov8n_float32.tflite"))
                print("ERFOLG: yolov8n_float32.tflite wurde erstellt!")
                return
        print("Konnte die tflite Datei nach der Konvertierung nicht finden.")
    except Exception as e:
        print(f"Konvertierung fehlgeschlagen: {e}")

convert()
