$projectPath = "C:\Users\Jonas\AndroidStudioProjects\helloWorld2"
$assetsPath = "$projectPath\app\src\main\assets"
$modelName = "yolov8n_float32.tflite"

# Ein verifizierter Mirror für das YOLOv8n TFLite Modell
$url = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_float32.tflite"

$outputPath = Join-Path $assetsPath $modelName

Write-Host "--- YOLO Modell-Setup wird gestartet ---" -ForegroundColor Cyan

# 1. Assets Ordner sicherstellen
if (!(Test-Path $assetsPath)) {
    Write-Host "[1/3] Erstelle Assets-Ordner..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $assetsPath -Force | Out-Null
}

# 2. Modell herunterladen
# Wir nutzen -UseBasicParsing um IE-Abhängigkeiten zu vermeiden
Write-Host "[2/3] Lade Modell herunter (ca. 12MB)..." -ForegroundColor Yellow
try {
    # Wir probieren erst Invoke-WebRequest, falls das fehlschlägt curl.exe
    Invoke-WebRequest -Uri $url -OutFile $outputPath -ErrorAction SilentlyContinue
    
    # Check ob der Download erfolgreich war (Größe > 1MB)
    if (!(Test-Path $outputPath) -or (Get-Item $outputPath).Length -lt 1MB) {
        Write-Host "Erster Versuch fehlgeschlagen, versuche alternativen Weg..." -ForegroundColor Gray
        & curl.exe -L "https://github.com/ultralytics/assets/releases/download/v8.1.0/yolov8n_float32.tflite" -o $outputPath
    }
} catch {
    & curl.exe -L $url -o $outputPath
}

# 3. Abschlussprüfung
if (Test-Path $outputPath) {
    $size = (Get-Item $outputPath).Length / 1MB
    if ($size -gt 5) {
        Write-Host "[3/3] ERFOLG! Modell bereit: $modelName ($($size.ToString('F2')) MB)" -ForegroundColor Green
        Write-Host "Die Datei wurde in den Assets deines Projekts gespeichert." -ForegroundColor Gray
    } else {
        Write-Host "[3/3] FEHLER: Die heruntergeladene Datei ist zu klein. Bitte lade sie manuell herunter: $url" -ForegroundColor Red
    }
} else {
    Write-Host "[3/3] FEHLER: Download konnte nicht gestartet werden." -ForegroundColor Red
}
