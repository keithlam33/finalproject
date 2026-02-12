#!/usr/bin/env bash
# Windows + Git Bash friendly venv setup script
VENV_NAME="bootcamp-env"
echo "🔍 Checking if already in a virtual environment..."
if [[ -n "$VIRTUAL_ENV" ]]; then
  echo "🔁 Deactivating current environment..."
  deactivate
fi
# Remove old venv if it exists (use cross-platform safe removal)
if [[ -d "$VENV_NAME" ]]; then
  echo "🗑️  Removing old virtual environment..."
  rm -rf "$VENV_NAME"
fi
echo "📦 Creating new virtual environment..."
python -m venv "$VENV_NAME"
echo "✅ Activating virtual environment..."
# Git Bash on Windows uses Scripts/activate (with forward slashes)
source "$VENV_NAME/Scripts/activate"
# Upgrade pip
echo "⬆️  Upgrading pip..."
python -m pip install --upgrade pip
# Install dependencies
if [[ -f "requirements.txt" ]]; then
  echo "📥 Installing packages from requirements.txt..."
  pip install -r requirements.txt
else
  echo "⚠️  No requirements.txt found — skipping pip install"
fi
# Register Jupyter kernel (useful for notebooks / VS Code)
echo "🔧 Installing ipykernel and registering kernel..."
pip install ipykernel --quiet
python -m ipykernel install --user --name="$VENV_NAME" --display-name "Python ($VENV_NAME)"
echo -e "\n----------------------------------------"
echo "Environment information:"
python --version
pip --version
# jupyter might not be installed yet — ignore error if missing
jupyter --version 2>/dev/null || echo "(jupyter not installed yet)"
echo "----------------------------------------"
echo -e "\n✅ Environment setup complete!"
echo "You are now inside $VENV_NAME"
echo "When finished, just type:  deactivate"