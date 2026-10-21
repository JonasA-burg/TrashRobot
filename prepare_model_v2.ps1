$projectPath = "C:\Users\Jonas\AndroidStudioProjects\helloWorld2"
$assetsPath = "$projectPath\app\src\main\assets"
$tempPath = "$projectPath\model_temp"

Write-Host "--- YOLOv8 Modell-Vorbereitung mit Python ---" -ForegroundColor Cyan

# 1. Ordner vorbereiten
if (!(Test-Path $assetsPath)) { New-Item -ItemType Directory -Path $assetsPath -Force | Out-Null }
if (!(Test-Path $tempPath)) { New-Item -ItemType Directory -Path $tempPath -Force | Out-Null }
Set-Location $tempPath

# 2. Ultralytics und TensorFlow installieren
Write-Host "[1/3] Installiere/Prüfe Bibliotheken (ultralytics, tensorflow)..." -ForegroundColor Yellow
python -m pip install --upgrade pip
python -m pip install ultralytics tensorflow

# 3. Modell laden und exportieren
Write-Host "[2/3] Exportiere Modell nach TFLite via Ultralytics (bitte warten)..." -ForegroundColor Yellow
$pythonScript = @"
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
    target_file = os.path.join(r'$assetsPath', 'yolov8n_float32.tflite')

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
"@

$pythonScript | Out-File -FilePath "export_yolo.py" -Encoding utf8
python export_yolo.py

# 4. Ergebnisprüfung
$finalModel = Join-Path $assetsPath "yolov8n_float32.tflite"
if (Test-Path $finalModel) {
    $size = (Get-Item $finalModel).Length / 1MB
    Write-Host "[3/3] ERFOLG! Modell bereit: yolov8n_float32.tflite ($($size.ToString('F2')) MB)" -ForegroundColor Green
    Write-Host "Das Modell wurde erfolgreich in den Assets-Ordner deiner App kopiert." -ForegroundColor Gray
} else {
    Write-Host "[3/3] FEHLER: Das Modell konnte nicht erstellt werden." -ForegroundColor Red
}

Set-Location $projectPath
