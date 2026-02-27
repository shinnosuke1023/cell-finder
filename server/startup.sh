#!/bin/bash
set -e

# Install dependencies
pip install --quiet -r requirements.txt

# Create persistent data directory for Azure
mkdir -p /home/data

# Start gunicorn - $PORT is set by Azure App Service
exec gunicorn --bind=0.0.0.0:${PORT:-8000} --timeout 120 --workers 2 --worker-class gthread --threads 8 wsgi:app
