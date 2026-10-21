$projectPath = "C:\Users\Jonas\AndroidStudioProjects\helloWorld2"
$assetsPath = "$projectPath\app\src\main\assets"
$modelName = "yolov8n_float32.tflite"

# Dieser Link führt direkt zur Datei in einem stabilen Release
$url = "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n_float32.tflite"

$outputPath = Join-Path $assetsPath $modelName

Write-Host "--- YOLO Modell-Fix wird gestartet ---" -ForegroundColor Cyan

# 1. Assets Ordner sicherstellen
if (!(Test-Path $assetsPath)) {
    New-Item -ItemType Directory -Path $assetsPath -Force | Out-Null
}

# 2. Modell herunterladen mit curl (folgt Redirects)
Write-Host "[1/2] Lade Modell herunter (ca. 12 MB)..." -ForegroundColor Yellow
& curl.exe -L -o $outputPath $url

# 3. Validierung
if (Test-Path $outputPath) {
    $size = (Get-Item $outputPath).Length / 1MB
    if ($size -gt 10) {
        Write-Host "[2/2] ERFOLG! Modell ist bereit ($($size.ToString('F2')) MB)." -ForegroundColor Green
        Write-Host "Die Datei liegt in: $outputPath" -ForegroundColor Gray
        Write-Host "`nDu kannst jetzt in Android Studio auf den grünen PLAY-Button klicken!" -ForegroundColor Cyan
    } else {
        Write-Host "[2/2] FEHLER: Der Download war nicht erfolgreich (Datei zu klein: $($size.ToString('F4')) MB)." -ForegroundColor Red
        Write-Host "Bitte versuche diesen Link manuell im Browser: $url" -ForegroundColor Yellow
        Remove-Item $outputPath
    }
} else {
    Write-Host "[2/2] FEHLER: Datei konnte nicht erstellt werden." -ForegroundColor Red
}
