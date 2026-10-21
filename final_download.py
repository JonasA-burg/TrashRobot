import requests
import os

def download_file(url, target_path):
    print(f"Versuche Download von: {url}")
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
    try:
        response = requests.get(url, headers=headers, stream=True, timeout=30)
        response.raise_for_status()
        with open(target_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        size = os.path.getsize(target_path) / (1024 * 1024)
        print(f"Erfolg! Größe: {size:.2f} MB")
        return True
    except Exception as e:
        print(f"Fehler: {e}")
        return False

assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
target = os.path.join(assets_dir, "yolov8n_float32.tflite")

if not os.path.exists(assets_dir):
    os.makedirs(assets_dir)

# Ein Link von einem offiziellen TensorFlow-Beispiel/Mirror
url1 = "https://github.com/sayakpaul/YOLOv8-TFLite-Object-Detection-Android/raw/main/app/src/main/assets/yolov8n_float32.tflite"

if not download_file(url1, target):
    print("Versuche Fallback-Mirror...")
    # Zweiter Versuch von einem anderen Tutorial-Repo
    url2 = "https://github.com/shubham0204/YOLOv8-Android-TFLite/raw/main/app/src/main/assets/yolov8n_float32.tflite"
    download_file(url2, target)
