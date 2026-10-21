import urllib.request
import os

def final_attempt():
    assets_dir = r"C:\Users\Jonas\AndroidStudioProjects\helloWorld2\app\src\main\assets"
    target_path = os.path.join(assets_dir, "yolov8n_float32.tflite")
    
    # Mirror-Link (huggingface ist meist sehr stabil)
    url = "https://huggingface.co/krishna-priya/yolov8n_float32.tflite/resolve/main/yolov8n_float32.tflite"
    
    if not os.path.exists(assets_dir):
        os.makedirs(assets_dir)

    print(f"Versuche Download von HuggingFace...")
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response, open(target_path, 'wb') as out_file:
            out_file.write(response.read())
        
        size = os.path.getsize(target_path) / (1024 * 1024)
        if size > 10:
            print(f"GESCHAFFT! Modell ist da: {size:.2f} MB")
            return True
    except Exception as e:
        print(f"Fehler: {e}")
    return False

if __name__ == "__main__":
    final_attempt()
