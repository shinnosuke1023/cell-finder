#!/bin/bash
# Azure App Service startup script for cell-finder server
# Set as the startup command in: az webapp config set --startup-file "startup.sh"

# Change to the directory containing this script (works on Azure and locally)
cd "$(dirname "$0")" || exit 1

gunicorn \
  --bind=0.0.0.0:8000 \
  --timeout=120 \
  --workers=2 \
  wsgi:app
