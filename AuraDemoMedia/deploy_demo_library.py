import subprocess
import os
import sys

# AURA PROMOTIONAL DEMO LIBRARY PIPELINE
# DEVICE DEPLOYMENT TOOL (PHASE 4)

ADB_PATH = "C:\\Users\\lance\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
DEVICE_TARGET_DIR = "/sdcard/Pictures/AuraDemoMedia"

def run_adb(args):
    try:
        result = subprocess.run([ADB_PATH] + args, capture_output=True, text=True, check=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error running adb: {e.stderr}")
        return None

def get_devices():
    output = run_adb(["devices"])
    if not output:
        return []
    lines = output.split("\n")[1:]
    devices = [line.split("\t")[0] for line in lines if line.strip() and "\tdevice" in line]
    return devices

def main():
    print("--- Aura Demo Library Deployment ---")

    force_yes = "--yes" in sys.argv

    devices = get_devices()
    android_serial = os.environ.get("ANDROID_SERIAL")

    if android_serial:
        if android_serial in devices:
            serial = android_serial
        else:
            print(f"ERROR: ANDROID_SERIAL='{android_serial}' is set but does not match any connected device.")
            if devices:
                print("Connected devices:")
                for d in devices:
                    print(f" - {d}")
            else:
                print("No connected devices detected.")
            sys.exit(1)
    else:
        if not devices:
            print("ERROR: No Android devices detected. Please connect a physical device.")
            sys.exit(1)

        if len(devices) > 1:
            print("ERROR: Multiple devices detected. Please connect only one target device.")
            for d in devices:
                print(f" - {d}")
            sys.exit(1)

        serial = devices[0]
    model = run_adb(["-s", serial, "shell", "getprop", "ro.product.model"])
    print(f"Target Device: {model} ({serial})")

    if not force_yes:
        confirm = input(f"Deploy promotional dataset to {model}? (y/n): ")
        if confirm.lower() != 'y':
            print("Deployment cancelled.")
            return
    else:
        print("Force-yes active. Proceeding with deployment...")

    print(f"Creating directory on device: {DEVICE_TARGET_DIR}")
    run_adb(["-s", serial, "shell", "mkdir", "-p", DEVICE_TARGET_DIR])

    current_dir = os.path.dirname(os.path.abspath(__file__))

    # Push Photos
    photos_dir = os.path.join(current_dir, "photos")
    if os.path.exists(photos_dir):
        print("Pushing photos...")
        run_adb(["-s", serial, "push", photos_dir, DEVICE_TARGET_DIR + "/"])

    # Push Videos
    videos_dir = os.path.join(current_dir, "videos")
    if os.path.exists(videos_dir):
        print("Pushing videos...")
        run_adb(["-s", serial, "push", videos_dir, DEVICE_TARGET_DIR + "/"])

    print("Triggering Android Media Scanner...")
    # This triggers a scan of the newly added files
    run_adb(["-s", serial, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{DEVICE_TARGET_DIR}"])

    print("\nDeployment SUCCESS.")
    print("Next Steps:")
    print("1. Launch Aura.")
    print("2. Wait for background indexing (Sync icon/ProgressBar).")
    print("3. Verify media appears in Library.")

if __name__ == "__main__":
    main()
