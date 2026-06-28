#!/bin/bash
# SnapKnow Development Environment Setup
# Run this script to set up the entire development environment

set -e

echo "════════════════════════════════════════════════════════════"
echo "  SnapKnow Development Setup"
echo "════════════════════════════════════════════════════════════"
echo ""

# Check Python version
PYTHON_VERSION=$(python3 --version 2>&1 | awk '{print $2}' | cut -d. -f1,2)
echo "[1/6] Checking Python version: $PYTHON_VERSION"
if [[ "$PYTHON_VERSION" < "3.10" ]]; then
    echo "ERROR: Python 3.10+ required. You have $PYTHON_VERSION"
    exit 1
fi
if [[ "$PYTHON_VERSION" > "3.11" ]]; then
    echo "WARNING: Python > 3.11 may have torch.export compatibility issues"
    echo "Recommended: Python 3.10 or 3.11"
fi
echo "✓ Python version OK"
echo ""

# Create virtual environment
echo "[2/6] Creating virtual environment..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
    echo "✓ Virtual environment created"
else
    echo "✓ Virtual environment already exists"
fi
echo ""

# Activate venv
echo "[3/6] Activating virtual environment..."
source venv/bin/activate
echo "✓ Virtual environment activated"
echo ""

# Upgrade pip
echo "[4/6] Upgrading pip..."
pip install --upgrade pip setuptools wheel > /dev/null 2>&1
echo "✓ pip upgraded"
echo ""

# Install dependencies
echo "[5/6] Installing Python dependencies..."
echo "This may take several minutes..."
pip install -r requirements-dev.txt
echo "✓ Dependencies installed"
echo ""

# Verify installation
echo "[6/6] Verifying installation..."
python3 -c "import torch; print(f'  • PyTorch: {torch.__version__}')"
python3 -c "import facenet_pytorch; print('  • facenet-pytorch: OK')"
python3 -c "import executorch; print('  • ExecuTorch: OK')" 2>/dev/null || echo "  ⚠ ExecuTorch: May need to be reinstalled separately"
echo ""

echo "════════════════════════════════════════════════════════════"
echo "  ✓ Setup Complete!"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Next steps:"
echo "  1. Build face embedding model:"
echo "     python rebuild_face_embedding_pt.py"
echo ""
echo "  2. (Optional) Build ExecuTorch variant:"
echo "     python export_face_embedding.py --output app/src/main/assets/face_embedding.pte"
echo ""
echo "  3. Build Android app:"
echo "     ./gradlew :app:assembleDebug"
echo ""
echo "  4. Install on device:"
echo "     adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
