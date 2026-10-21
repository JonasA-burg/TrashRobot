from ultralytics import YOLO
import shutil
import os

# Modell laden (lädt .pt Datei automatisch von Ultralytics)
model = YOLO('yolov8n.pt')

# Export nach TFLite (Float32)
# int8=False sorgt für float32
export_path = model.export(format='tflite', imgsz=640)

# In den Assets Ordner kopieren
target_dir = r'C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets'
target_file = os.path.join(target_dir, 'yolov8n_float32.tflite')

# Die exportierte Datei finden (heißt meist yolov8n_saved_model/yolov8n_float32.tflite oder ähnlich)
# Ultralytics gibt den Pfad zurück
if os.path.exists(export_path):
    # Wenn export_path ein Ordner ist (saved_model), suchen wir die .tflite darin
    if os.path.isdir(export_path):
        for f in os.listdir(export_path):
            if f.endswith('.tflite'):
                shutil.copy(os.path.join(export_path, f), target_file)
                print(f'SUCCESS:{target_file}')
    else:
        shutil.copy(export_path, target_file)
        print(f'SUCCESS:{target_file}')
