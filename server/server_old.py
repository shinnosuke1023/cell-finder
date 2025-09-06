from flask import Flask, request, jsonify
from flask_cors import CORS
import sqlite3

DB_FILE = 'cell_log.db'
app = Flask(__name__)
CORS(app)

def init_db():
    conn = sqlite3.connect(DB_FILE)
    cur = conn.cursor()
    cur.execute('''
    CREATE TABLE IF NOT EXISTS cell_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp INTEGER,
        lat REAL,
        lon REAL,
        sim_state TEXT,
        found_gsm INTEGER,
        any_type_known INTEGER,
        type TEXT,
        dbm INTEGER,
        has_identity INTEGER
    )
    ''')
    conn.commit()
    conn.close()

@app.route('/log', methods=['POST'])
def log_data():
    data = request.get_json()
    if not data:
        return jsonify({'error': 'no json'}), 400
    conn = sqlite3.connect(DB_FILE)
    cur = conn.cursor()
    sim_state = data.get('simState')
    found_gsm = 1 if data.get('foundGsmType') else 0
    any_known = 1 if data.get('anyTypeKnown') else 0
    for cell in data.get('cells', []):
        cur.execute('INSERT INTO cell_log (timestamp, lat, lon, sim_state, found_gsm, any_type_known, type, dbm, has_identity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', (
            data.get('timestamp'), data.get('lat'), data.get('lon'),
            sim_state, found_gsm, any_known,
            cell.get('type'), cell.get('dbm'), 1 if cell.get('hasIdentity') else 0
        ))
    conn.commit()
    conn.close()
    return jsonify({'status': 'ok'})

@app.route('/map')
def map_view():
    conn = sqlite3.connect(DB_FILE)
    cur = conn.cursor()
    cur.execute('SELECT lat, lon, type, dbm, sim_state, found_gsm FROM cell_log WHERE lat IS NOT NULL AND lon IS NOT NULL')
    rows = cur.fetchall()
    conn.close()

    markers = ''
    for lat, lon, typ, dbm, sim_state, found_gsm in rows:
        popup = f"{typ}: {dbm} dBm<br>SIM:{sim_state}<br>foundGSM:{found_gsm}"
        markers += f"L.marker([{lat}, {lon}]).addTo(map).bindPopup('{popup}');\n"

    html = '''
    <html><head><meta charset="utf-8"><title>Cell Map</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet/dist/leaflet.js"></script>
    </head><body>
    <div id="map" style="width:100%; height:100vh;"></div>
    <script>
      var map = L.map('map').setView([35.0, 139.0], 13);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);
    '''
    html += f'''
      {markers}
    </script>
    </body></html>
    '''
    return html

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5000)
