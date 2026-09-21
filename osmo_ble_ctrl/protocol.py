"""Minimal DJI DUML protocol used by Osmo Nano over BLE.

The framing and Nano command mappings are derived from Action Multicam Remote;
see THIRD_PARTY_NOTICES.md for source and license details.
"""

from __future__ import annotations

from dataclasses import dataclass

APP_ADDRESS = 0x02
CAMERA_ADDRESS = 0x01
WIFI_ADDRESS = 0x07
SESSION_ADDRESS = 0xF0
SESSION_INFO_ADDRESS = 0x88


def _crc8_step(value: int) -> int:
    for _ in range(8):
        value = (value >> 1) ^ 0x8C if value & 1 else value >> 1
    return value


def crc8(data: bytes) -> int:
    checksum = 0x77
    for byte in data:
        checksum = _crc8_step(checksum ^ byte)
    return checksum


def crc16(data: bytes) -> int:
    checksum = 0x3692
    for byte in data:
        value = checksum ^ byte
        for _ in range(8):
            value = (value >> 1) ^ 0x8408 if value & 1 else value >> 1
        checksum = value
    return checksum


def frame(
    sequence: int,
    destination: int,
    command_set: int,
    command_id: int,
    payload: bytes = b"",
    *,
    source: int = APP_ADDRESS,
    flags: int = 0x40,
) -> bytes:
    length = 13 + len(payload)
    if length > 0x3FF:
        raise ValueError("DUML frame is too long")
    version_and_length = length | (1 << 10)
    packet = bytearray((0x55, version_and_length & 0xFF, version_and_length >> 8))
    packet.append(crc8(packet))
    packet.extend((source, destination, (sequence >> 8) & 0xFF, sequence & 0xFF,
                   flags, command_set, command_id))
    packet.extend(payload)
    checksum = crc16(packet)
    packet.extend((checksum & 0xFF, checksum >> 8))
    return bytes(packet)


def packed_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > 255:
        raise ValueError("packed string is too long")
    return bytes((len(encoded),)) + encoded


def pairing_payload(identifier: str, token: str) -> bytes:
    return packed_string(identifier) + packed_string(token)


@dataclass(frozen=True)
class Packet:
    source: int
    destination: int
    sequence: int
    flags: int
    command_set: int
    command_id: int
    payload: bytes

    @property
    def is_response(self) -> bool:
        return bool(self.flags & 0x80)

    @classmethod
    def parse(cls, data: bytes) -> "Packet":
        if len(data) < 13 or data[0] != 0x55:
            raise ValueError("not a complete DUML frame")
        declared = int.from_bytes(data[1:3], "little") & 0x03FF
        if declared != len(data):
            raise ValueError("DUML length mismatch")
        if data[3] != crc8(data[:3]):
            raise ValueError("DUML header CRC mismatch")
        expected = int.from_bytes(data[-2:], "little")
        if expected != crc16(data[:-2]):
            raise ValueError("DUML packet CRC mismatch")
        return cls(data[4], data[5], int.from_bytes(data[6:8], "big"), data[8],
                   data[9], data[10], data[11:-2])


class FrameParser:
    """Reassemble split/combined notifications and discard corrupt frames."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.errors: list[str] = []

    def clear(self) -> None:
        self._buffer.clear()
        self.errors.clear()

    def feed(self, data: bytes) -> list[Packet]:
        self._buffer.extend(data)
        packets: list[Packet] = []
        while self._buffer:
            try:
                start = self._buffer.index(0x55)
            except ValueError:
                self._buffer.clear()
                break
            if start:
                del self._buffer[:start]
            if len(self._buffer) < 4:
                break
            length = int.from_bytes(self._buffer[1:3], "little") & 0x03FF
            if length < 13:
                self.errors.append("invalid length")
                del self._buffer[0]
                continue
            if self._buffer[3] != crc8(bytes(self._buffer[:3])):
                self.errors.append("header CRC mismatch")
                del self._buffer[0]
                continue
            if len(self._buffer) < length:
                break
            raw = bytes(self._buffer[:length])
            del self._buffer[:length]
            try:
                packets.append(Packet.parse(raw))
            except ValueError as exc:
                self.errors.append(str(exc))
        return packets


def recording_state_from_push(packet: Packet) -> str | None:
    """Return confirmed state only for unsolicited camera-state pushes."""
    if packet.is_response or (packet.command_set, packet.command_id) not in {(0x02, 0x80), (0x0D, 0x02)}:
        return None
    payload = packet.payload
    if len(payload) >= 37:  # full Nano state; this is authoritative
        flags = int.from_bytes(payload[0:4], "little")
        mode = payload[4]
        if mode == 0x01:
            return "recording" if flags & 0x00C0 else "stopped"
        if mode <= 0x0F:
            return "stopped"
    # Nano compact bit 0x40 can mean the display/battery dock is attached. It
    # is therefore not safe recording confirmation without timer history.
    return None
