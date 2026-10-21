$assetsPath = "C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
$modelName = "yolov8n_float32.tflite"
$url = "https://github.com/ultralytics/assets/releases/download/v8.1.0/yolov8n_float32.tflite"
$output = Join-Path $assetsPath $modelName

if (!(Test-Path $assetsPath)) {
    Write-Host "Erstelle Assets-Ordner..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $assetsPath -Force
}

Write-Host "--- Starte Download von $modelName ---" -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri $url -OutFile $output -ErrorAction Stop
    Write-Host "--- Erfolg! Modell wurde abgelegt in: $output ---" -ForegroundColor Green
} catch {
    Write-Host "Fehler beim Download: $_" -ForegroundColor Red
}
