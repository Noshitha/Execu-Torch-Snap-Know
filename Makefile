.PHONY: help setup build-model build-model-executorch build-model-whisper build-apk install-apk clean

help:
	@echo "SnapKnow Development Commands"
	@echo ""
	@echo "Setup & Installation:"
	@echo "  make setup              - Initialize Python environment"
	@echo ""
	@echo "Model Building:"
	@echo "  make build-model        - Build PyTorch Mobile face embedding (recommended)"
	@echo "  make build-model-executorch - Build ExecuTorch face embedding (NPU optimized)"
	@echo "  make build-model-whisper    - Build Whisper speech models (experimental)"
	@echo ""
	@echo "Android Build & Deploy:"
	@echo "  make build-apk          - Build Android APK (debug)"
	@echo "  make install-apk        - Install APK to connected device"
	@echo "  make build-release      - Build release APK"
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean              - Remove build artifacts"
	@echo ""

setup:
	@bash setup.sh

build-model:
	@echo "Building PyTorch Mobile face embedding model..."
	@python rebuild_face_embedding_pt.py
	@echo "✓ Model saved to app/src/main/assets/face_embedding.pt"

build-model-executorch:
	@echo "Building ExecuTorch face embedding model (XNNPACK)..."
	@python export_face_embedding.py --output app/src/main/assets/face_embedding.pte --verify
	@echo "✓ Model saved to app/src/main/assets/face_embedding.pte"

build-model-executorch-qnn:
	@echo "Building ExecuTorch face embedding model (QNN)..."
	@python export_face_embedding.py --output app/src/main/assets/face_embedding_qnn.pte --qnn --verify
	@echo "✓ Model saved to app/src/main/assets/face_embedding_qnn.pte"

build-model-whisper:
	@echo "Building Whisper speech models..."
	@python export_whisper_tiny.py --out_dir app/src/main/assets/
	@echo "✓ Models saved to app/src/main/assets/"

build-apk:
	@echo "Building Android APK (debug)..."
	@./gradlew :app:assembleDebug
	@echo "✓ APK ready: app/build/outputs/apk/debug/app-debug.apk"

build-release:
	@echo "Building Android APK (release)..."
	@./gradlew :app:assembleRelease
	@echo "✓ APK ready: app/build/outputs/apk/release/app-release.apk"

install-apk:
	@echo "Installing APK to connected device..."
	@adb install -r app/build/outputs/apk/debug/app-debug.apk
	@echo "✓ APK installed"
	@echo "Launching app..."
	@adb shell am start -n com.snapknow.app/com.snapknow.app.MainActivity

clean:
	@echo "Cleaning build artifacts..."
	@./gradlew clean
	@rm -rf build/
	@rm -rf app/build/
	@echo "✓ Clean complete"

all: setup build-model build-apk install-apk
	@echo ""
	@echo "✓ Full setup complete! App should now be running on your device."
