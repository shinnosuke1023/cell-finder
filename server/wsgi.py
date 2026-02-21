"""WSGI entry-point for Azure App Service (and other WSGI hosts).

Azure App Service の永続化領域 /home/data/ を DB ディレクトリとして使う。
環境変数 WEBSITE_SITE_NAME が設定されていれば Azure 上と判断する。
"""

import os

# Azure 上では /home/data/ を永続化ストレージとして使う
if os.environ.get("WEBSITE_SITE_NAME"):
    data_dir = "/home/data"
    os.makedirs(data_dir, exist_ok=True)
    os.environ.setdefault("REALTIME_DB", os.path.join(data_dir, "cells.db"))
    os.environ.setdefault("ARCHIVE_DB",  os.path.join(data_dir, "archive.db"))

from server import app  # noqa: E402  (must come after env vars are set)

if __name__ == "__main__":
    app.run()
