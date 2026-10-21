from ultralytics import YOLO
import shutil
import os

try:
    # 1. Modell laden (lädt .pt Datei automatisch herunter)
    model = YOLO('yolov8n.pt')

    # 2. Export nach TFLite
    # imgsz=640 ist Standard. int8=False für float32.
    # Der Export erzeugt oft einen Ordner oder eine Datei.
    export_path = model.export(format='tflite', imgsz=640)
    print(f'Export Path: {export_path}')

    # 3. Zieldatei definieren
    target_file = os.path.join(r'C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets', 'yolov8n_float32.tflite')

    # 4. Datei finden und kopieren
    # Ultralytics exportiert oft nach 'yolov8n_saved_model/yolov8n_float32.tflite'
    # oder direkt als 'yolov8n_float32.tflite'
    if os.path.isdir(export_path):
        found = False
        for root, dirs, files in os.walk(export_path):
            for f in files:
                if f.endswith('.tflite'):
                    shutil.copy(os.path.join(root, f), target_file)
                    print(f'SUCCESS:{target_file}')
                    found = True
                    break
            if found: break
    elif os.path.exists(export_path):
        shutil.copy(export_path, target_file)
        print(f'SUCCESS:{target_file}')
    else:
        print('ERROR: Export file not found.')

except Exception as e:
    print(f'ERROR: {str(e)}')
