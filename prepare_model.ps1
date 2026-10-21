$projectPath = "C:\Users\Jonas\AndroidStudioProjects\helloWorld2"
$assetsPath = "$projectPath\app\src\main\assets"
$tempPath = "$projectPath\model_temp"

Write-Host "--- YOLOv8 Modell-Vorbereitung ---" -ForegroundColor Cyan

# 1. Ordner vorbereiten
if (!(Test-Path $assetsPath)) { New-Item -ItemType Directory -Path $assetsPath -Force }
if (!(Test-Path $tempPath)) { New-Item -ItemType Directory -Path $tempPath -Force }
Set-Location $tempPath

# 2. Python-Umgebung prüfen und Ultralytics nutzen
Write-Host "[1/3] Installiere/Prüfe Ultralytics..." -ForegroundColor Yellow
# Wir versuchen Python über verschiedene Befehle aufzurufen
$pythonCmd = "python"
try { & $pythonCmd --version } catch { $pythonCmd = "py" }

# Installiere ultralytics falls nicht vorhanden
& $pythonCmd -m pip install ultralytics tensorflow

# 3. Modell laden und exportieren (Python-Teil)
Write-Host "[2/3] Exportiere Modell nach TFLite (das kann einen Moment dauern)..." -ForegroundColor Yellow
$pythonScript = @"
from ultralytics import YOLO
import shutil
import os

# Modell laden (lädt .pt Datei automatisch von Ultralytics)
model = YOLO('yolov8n.pt')

# Export nach TFLite (Float32)
# int8=False sorgt für float32
export_path = model.export(format='tflite', imgsz=640)

# In den Assets Ordner kopieren
target_dir = r'$assetsPath'
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
"@

$pythonScript | Out-File -FilePath "export_model.py" -Encoding utf8
$result = & $pythonCmd export_model.py

# 4. Ergebnis prüfen
if ($result -like "*SUCCESS:*") {
    Write-Host "[3/3] ERFOLG! Das Modell wurde erstellt und in den Assets abgelegt." -ForegroundColor Green
    Write-Host "Du kannst die App jetzt in Android Studio mit dem PLAY-Button starten." -ForegroundColor Cyan
} else {
    Write-Host "[3/3] FEHLER beim Export. Bitte stelle sicher, dass Python und Pip installiert sind." -ForegroundColor Red
    Write-Host $result
}

# Aufräumen
# Set-Location $projectPath
# Remove-Item $tempPath -Recurse -Force -ErrorAction SilentlyContinue
