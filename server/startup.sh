#!/bin/bash
set -e

# Install dependencies
pip install --quiet -r requirements.txt

# Create persistent data directory for Azure
mkdir -p /home/data

# Remove leftover WAL/SHM files – WAL mode does not work on Azure's SMB mount.
# init_db() will convert the DB to DELETE journal mode on startup.
rm -f /home/data/*.db-wal /home/data/*.db-shm

# Start gunicorn - $PORT is set by Azure App Service
# Workers=1: Azure B1 has 1 CPU core; multiple workers cause file contention on SMB
exec gunicorn --bind=0.0.0.0:${PORT:-8000} --timeout 120 --workers 1 --worker-class gthread --threads 8 wsgi:app
