#!/usr/bin/env python3
"""
NGUMN ESP32 simulator.

This script is a SOFTWARE STAND-IN for a real ESP32 + GPS module. It
publishes the exact same MQTT payload shape that real ESP32 firmware
would, to the same topic (`ngumn/vehicle/{vehicleCode}/location`), so
the NGUMN backend's MQTT bridge can be demonstrated end-to-end without
physical hardware.

It is clearly a simulator, not firmware - it is documented as such
here and in IMPLEMENTATION_STATUS.md, and should always be presented
that way.

Prerequisites:
    pip install paho-mqtt
    A running MQTT broker (e.g. `mosquitto -p 1883`)
    Backend running with MQTT_ENABLED=true and matching MQTT_BROKER_URL

Usage:
    python3 esp32_simulator.py --vehicle-code DEMO-ESP32-1
    python3 esp32_simulator.py --vehicle-code DEMO-ESP32-1 \
        --broker tcp://localhost:1883 --lat 17.3850 --lon 78.4867

The vehicle code must already exist in the NGUMN database (register a
vehicle via the app or API first, and use its `vehicleCode`) - the
backend looks up the vehicle by code on each incoming message and
updates its position, exactly like a real device would.
"""

import argparse
import json
import math
import random
import time
from urllib.parse import urlparse

try:
    import paho.mqtt.client as mqtt
except ImportError:
    raise SystemExit(
        "Missing dependency 'paho-mqtt'. Install it with:\n"
        "    pip3 install paho-mqtt\n"
    )


def parse_broker_url(url: str):
    """Accepts 'tcp://host:port' (as used in application.properties) and
    returns (host, port)."""
    parsed = urlparse(url)
    host = parsed.hostname or "localhost"
    port = parsed.port or 1883
    return host, port


def simulate_step(lat: float, lon: float, heading_deg: float):
    """Nudges the position a small random amount to look like real GPS
    drift/movement - not physically accurate, just enough to be visibly
    "live" on the Live Map screen."""
    step_deg = 0.00015  # ~15-20 metres per tick
    heading_deg += random.uniform(-15, 15)
    rad = math.radians(heading_deg)
    lat += step_deg * math.cos(rad)
    lon += step_deg * math.sin(rad)
    speed_kmh = max(0, random.gauss(35, 8))
    return lat, lon, heading_deg, speed_kmh


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--vehicle-code", required=True, help="Existing vehicle's code, e.g. DEMO-ESP32-1")
    parser.add_argument("--broker", default="tcp://localhost:1883", help="MQTT broker URL")
    parser.add_argument("--lat", type=float, default=17.3850, help="Starting latitude (default: Hyderabad)")
    parser.add_argument("--lon", type=float, default=78.4867, help="Starting longitude (default: Hyderabad)")
    parser.add_argument("--interval", type=float, default=5.0, help="Seconds between publishes")
    parser.add_argument("--emergency", action="store_true", help="Mark this simulated vehicle as an active emergency vehicle")
    args = parser.parse_args()

    host, port = parse_broker_url(args.broker)
    topic = f"ngumn/vehicle/{args.vehicle_code}/location"

    client = mqtt.Client(client_id=f"esp32-sim-{args.vehicle_code}")
    print(f"Connecting to MQTT broker at {host}:{port} ...")
    client.connect(host, port, keepalive=30)
    client.loop_start()

    lat, lon, heading = args.lat, args.lon, 0.0
    print(f"Publishing simulated GPS for '{args.vehicle_code}' to '{topic}' every {args.interval}s. Ctrl+C to stop.")

    try:
        while True:
            lat, lon, heading, speed_kmh = simulate_step(lat, lon, heading)
            payload = {
                "latitude": round(lat, 6),
                "longitude": round(lon, 6),
                "speedKmh": round(speed_kmh, 1),
                "directionDegrees": round(heading % 360, 1),
                "emergencyStatus": args.emergency,
            }
            client.publish(topic, json.dumps(payload))
            print(f"  -> {payload}")
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nStopping simulator.")
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
