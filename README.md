# Hello World YOLO App

Diese App ermöglicht es, die Kamera zu nutzen, um ein Foto aufzunehmen und das erste erkannte Objekt mittels YOLOv8 (TensorFlow Lite) zu markieren.

## Funktionen
- **Kamera-Vorschau**: Nutzt CameraX für eine flüssige Live-Vorschau.
- **Foto-Aufnahme**: Erfasst ein Standbild zur Analyse.
- **Objekterkennung**: Analysiert das Bild mit einem YOLOv8-Modell.
- **Visualisierung**: Zeichnet ein Rechteck (Bounding Box) um das am besten erkannte Objekt.

## Installation & Einrichtung
1. **Modell-Datei**: Lade ein `yolov8n_float32.tflite` Modell herunter und lege es in den Ordner `app/src/main/assets/` ab.
2. **Berechtigungen**: Die App fragt beim Start nach der Kamera-Berechtigung.
3. **Abhängigkeiten**: Die nötigen Bibliotheken (CameraX, TensorFlow Lite) sind in `build.gradle.kts` konfiguriert.

## Projektstruktur
- `MainActivity.kt`: Enthält die Compose-UI und die Kamera-Logik.
- `ObjectDetector.kt`: Beinhaltet die TFLite-Interpreter-Logik und das Post-Processing für YOLO.
- `ObjectDetectorTest.kt`: Basis-Unit-Tests für die Datenstrukturen.

## Nutzung
1. Starten Sie die App.
2. Erteilen Sie die Kamera-Erlaubnis.
3. Richten Sie die Kamera auf ein Objekt und drücken Sie "Foto aufnehmen".
4. Das erkannte Objekt wird mit einem roten Rahmen und dem Label angezeigt.
5. Drücken Sie "Neues Foto", um zurück zur Kamera zu kehren.
