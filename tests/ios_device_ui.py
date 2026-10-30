#!/usr/bin/env python3
import sys
import socket
import struct
import plistlib
import urllib.parse
import xml.etree.ElementTree as ET
import subprocess
import time

UDID = "00008020-001325320A08002E"
PORT = 8100
TUNNEL_IP = "fd1d:eb47:cd4e::1"

def get_usb_device_id(target_udid: str) -> int:
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect("/var/run/usbmuxd")
    body = plistlib.dumps({
        "MessageType": "ListDevices",
        "ClientVersionString": "ios_usb_ui",
        "ProgName": "ios_usb_ui",
    })
    hdr = struct.pack("<IIII", 16 + len(body), 1, 8, 1)
    s.sendall(hdr + body)
    resp_hdr = s.recv(16)
    length, ver, msg, tag = struct.unpack("<IIII", resp_hdr)
    payload = b""
    while len(payload) < length - 16:
        chunk = s.recv(length - 16 - len(payload))
        if not chunk:
            break
        payload += chunk
    s.close()
    resp = plistlib.loads(payload)
    for d in resp.get("DeviceList", []):
        props = d.get("Properties", {})
        if props.get("SerialNumber") == target_udid and props.get("ConnectionType") == "USB":
            return d["DeviceID"]
    raise RuntimeError(f"Physical iPhone {target_udid} not found on USB via usbmuxd")

def connect_socket():
    # Try usbmuxd first
    try:
        dev_id = get_usb_device_id(UDID)
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(5.0)
        s.connect("/var/run/usbmuxd")
        net_port = socket.htons(PORT)
        body = plistlib.dumps({
            "MessageType": "Connect",
            "ClientVersionString": "ios_usb_ui",
            "ProgName": "ios_usb_ui",
            "DeviceID": dev_id,
            "PortNumber": net_port,
        })
        hdr = struct.pack("<IIII", 16 + len(body), 1, 8, 2)
        s.sendall(hdr + body)
        resp_hdr = s.recv(16)
        length, ver, msg, tag = struct.unpack("<IIII", resp_hdr)
        payload = b""
        while len(payload) < length - 16:
            chunk = s.recv(length - 16 - len(payload))
            if not chunk:
                break
            payload += chunk
        resp = plistlib.loads(payload)
        if resp.get("Number") == 0:
            return s
        s.close()
    except Exception:
        pass

    # Direct CoreDevice IPv6 tunnel
    s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    s.settimeout(5.0)
    s.connect((TUNNEL_IP, PORT))
    return s

def usb_http_request(path_and_query: str) -> bytes:
    last_err = None
    for attempt in range(6):
        try:
            s = connect_socket()
            req = f"GET {path_and_query} HTTP/1.1\r\nHost: localhost:{PORT}\r\nConnection: close\r\n\r\n".encode("utf-8")
            s.sendall(req)
            chunks = []
            while True:
                data = s.recv(65536)
                if not data:
                    break
                chunks.append(data)
            s.close()
            raw = b"".join(chunks)
            if b"\r\n\r\n" in raw:
                return raw.split(b"\r\n\r\n", 1)[1]
            return raw
        except Exception as e:
            last_err = e
            if attempt == 1:
                subprocess.run(["xcrun", "devicectl", "device", "process", "launch", "--device", UDID, "com.ryanmacarthur.foundry"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                time.sleep(2.0)
            else:
                time.sleep(0.5)
    raise last_err

def dump_elements(print_raw=False):
    xml_bytes = usb_http_request("/source")
    xml_str = xml_bytes.decode("utf-8", errors="replace")
    if print_raw:
        print(xml_str)
        return
    root = ET.fromstring(xml_str)
    for elem in root.iter("Element"):
        label = elem.attrib.get("label", "")
        desc = elem.attrib.get("content-desc", "")
        val = elem.attrib.get("value", "")
        etype = elem.attrib.get("type", "")
        clickable = elem.attrib.get("clickable", "false")
        focusable = elem.attrib.get("focusable", "false")
        focused = elem.attrib.get("focused", "false")
        scrollable = elem.attrib.get("scrollable", "false")
        bounds = elem.attrib.get("bounds", "")
        x = int(elem.attrib.get("x", "0"))
        y = int(elem.attrib.get("y", "0"))
        w = int(elem.attrib.get("width", "0"))
        h = int(elem.attrib.get("height", "0"))
        cx = x + w // 2
        cy = y + h // 2
        print(f"bounds={bounds} center=({cx},{cy}) type={etype} clickable={clickable} focusable={focusable} focused={focused} scrollable={scrollable} label={label!r} desc={desc!r} value={val!r}")

def main():
    if len(sys.argv) < 2:
        print("Usage: ios_device_ui.py [status|source|raw_source|tap X Y|swipe X1 Y1 X2 Y2 [STEPS]|keys TEXT|screenshot DEST]")
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "status":
        print(usb_http_request("/status").decode("utf-8"))
    elif cmd == "source":
        dump_elements(print_raw=False)
    elif cmd == "raw_source":
        dump_elements(print_raw=True)
    elif cmd == "tap":
        x, y = sys.argv[2], sys.argv[3]
        print(f"[IPHONE {UDID} TAP] ({x}, {y}) ->", usb_http_request(f"/tap?x={x}&y={y}").decode("utf-8"))
    elif cmd == "swipe":
        x1, y1, x2, y2 = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
        steps = sys.argv[6] if len(sys.argv) > 6 else "15"
        print(f"[IPHONE {UDID} SWIPE] ({x1},{y1})->({x2},{y2}) ->", usb_http_request(f"/swipe?x1={x1}&y1={y1}&x2={x2}&y2={y2}&steps={steps}").decode("utf-8"))
    elif cmd == "keys":
        text = urllib.parse.quote(sys.argv[2], safe="")
        print(f"[IPHONE {UDID} KEYS] {sys.argv[2]!r} ->", usb_http_request(f"/keys?text={text}").decode("utf-8"))
    elif cmd == "run":
        print(f"[IPHONE {UDID} RUN] ->", usb_http_request("/run").decode("utf-8"))
    elif cmd == "run_pointer":
        x = sys.argv[2] if len(sys.argv) > 2 else "0.5"
        y = sys.argv[3] if len(sys.argv) > 3 else "0.5"
        print(f"[IPHONE {UDID} RUN_POINTER] ({x}, {y}) ->", usb_http_request(f"/run_pointer?x={x}&y={y}").decode("utf-8"))
    elif cmd == "screenshot":
        dest = sys.argv[2]
        subprocess.run(["xcrun", "devicectl", "device", "capture", "screenshot", "--device", UDID, "--destination", dest], check=True)
    else:
        raise ValueError(f"Unknown command: {cmd}")

if __name__ == "__main__":
    main()
