#!/usr/bin/env python3
"""Minimal Modbus TCP responder for the profile experiment."""

from __future__ import annotations

import socket

HOST = "0.0.0.0"
PORT = 1502
REGISTER_VALUE = 42


def receive_exactly(connection: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise ConnectionError("client closed the connection")
        data.extend(chunk)
    return bytes(data)


def main() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((HOST, PORT))
        server.listen()
        print(f"Mock Modbus TCP responder listening on {HOST}:{PORT}", flush=True)
        while True:
            connection, _ = server.accept()
            with connection:
                try:
                    header = receive_exactly(connection, 7)
                    transaction_id = header[:2]
                    protocol_id = header[2:4]
                    request_length = int.from_bytes(header[4:6], "big")
                    unit_id = header[6:7]
                    request = receive_exactly(connection, request_length - 1)
                    if protocol_id != b"\x00\x00" or request[:1] != b"\x03":
                        continue
                    response = transaction_id + protocol_id + b"\x00\x05" + unit_id + b"\x03\x02"
                    connection.sendall(response + REGISTER_VALUE.to_bytes(2, "big"))
                except ConnectionError:
                    continue


if __name__ == "__main__":
    main()
