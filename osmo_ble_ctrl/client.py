from __future__ import annotations

import asyncio
import contextlib
from dataclasses import dataclass
from typing import Callable

from bleak import BleakClient, BleakScanner

from .protocol import (
    APP_ADDRESS, CAMERA_ADDRESS, SESSION_ADDRESS, SESSION_INFO_ADDRESS,
    WIFI_ADDRESS, FrameParser, Packet, frame, pairing_payload,
    recording_state_from_push,
)

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff4-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff5-0000-1000-8000-00805f9b34fb"
PAIRING_TOKEN = "OSMOCTRL"
WRITE_GAP = 0.12


@dataclass(frozen=True)
class DeviceInfo:
    name: str
    address: str
    rssi: int | None


EventCallback = Callable[[str, object], None]


def advertisement_is_nano(name: str, manufacturer_data: dict[int, bytes]) -> bool:
    lowered = name.lower()
    named_nano = "nano" in lowered and ("osmo" in lowered or "dji" in lowered)
    model_nano = any(
        (company == 0x08AA and len(data) >= 1 and data[0] == 0x19)
        or (len(data) >= 3 and data[:3] == b"\xaa\x08\x19")
        for company, data in manufacturer_data.items()
    )
    return named_nano or model_nano


class NanoClient:
    def __init__(self, identifier: str, callback: EventCallback) -> None:
        self.identifier = identifier
        self.callback = callback
        self._client: BleakClient | None = None
        self._sequence = 0
        self._generation = 0
        self._parser = FrameParser()
        self._writes: asyncio.Queue[tuple[bytes, str]] = asyncio.Queue()
        self._writer_task: asyncio.Task[None] | None = None
        self._keepalive_task: asyncio.Task[None] | None = None
        self._status_task: asyncio.Task[None] | None = None
        self._pairing_timeout_task: asyncio.Task[None] | None = None
        self._pending_timeout_task: asyncio.Task[None] | None = None
        self._ready = False
        self._pending_action: str | None = None
        self._pending_sequences: dict[int, str] = {}

    @property
    def connected(self) -> bool:
        return bool(self._client and self._client.is_connected)

    async def scan(self, timeout: float = 5.0) -> list[DeviceInfo]:
        self.callback("status", "検索中…")
        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
        devices: list[DeviceInfo] = []
        for device, advertisement in found.values():
            name = device.name or advertisement.local_name or "名前なし"
            service_uuids = {value.lower() for value in advertisement.service_uuids}
            if advertisement_is_nano(name, advertisement.manufacturer_data):
                devices.append(DeviceInfo(name, device.address, advertisement.rssi))
        devices.sort(key=lambda item: item.rssi if item.rssi is not None else -999, reverse=True)
        self.callback("devices", devices)
        self.callback("status", f"{len(devices)}台見つかりました" if devices else "Osmo Nanoが見つかりません")
        return devices

    async def connect(self, address: str) -> None:
        await self.disconnect()
        self._generation += 1
        generation = self._generation
        self._parser.clear()
        self._ready = False
        self.callback("recording", "unknown")
        self.callback("status", "接続中…")
        self.callback("connecting", True)

        def disconnected(_: BleakClient) -> None:
            loop.call_soon_threadsafe(lambda: asyncio.create_task(self._unexpected_disconnect(generation)))

        loop = asyncio.get_running_loop()
        client = BleakClient(address, disconnected_callback=disconnected)
        self._client = client
        try:
            await client.connect(timeout=15.0)
            if generation != self._generation or self._client is not client:
                await client.disconnect()
                return

            def notified(_: object, data: bytearray) -> None:
                copied = bytes(data)
                loop.call_soon_threadsafe(lambda: asyncio.create_task(self._on_notification(generation, copied)))

            await asyncio.wait_for(client.start_notify(NOTIFY_UUID, notified), timeout=10.0)
            if generation != self._generation or self._client is not client:
                await client.disconnect()
                return
            characteristic = client.services.get_characteristic(WRITE_UUID)
            if characteristic is None:
                raise RuntimeError("NanoのFFF5 write characteristicがありません")
            pairing_size = 13 + len(pairing_payload(self.identifier, PAIRING_TOKEN))
            if characteristic.max_write_without_response_size < pairing_size:
                raise RuntimeError(
                    f"BLE書込上限が{characteristic.max_write_without_response_size} byteで、"
                    f"ペアリングフレーム{pairing_size} byteを送れません"
                )
            self._writer_task = asyncio.create_task(self._writer(generation))
            await self._queue_command(SESSION_ADDRESS, 0x00, 0x2B, b"\x04\x00", "セッション開始")
            await self._queue_command(WIFI_ADDRESS, 0x07, 0x45,
                                      pairing_payload(self.identifier, PAIRING_TOKEN), "アプリ内ペアリング")
            await self._queue_command(SESSION_ADDRESS, 0x00, 0x2B, b"\x01\x01", "keepalive")
            self._keepalive_task = asyncio.create_task(self._keepalive(generation))
            self._pairing_timeout_task = asyncio.create_task(self._pairing_timeout(generation))
            self.callback("status", f"接続済み。初回はNano画面で {PAIRING_TOKEN} を承認してください")
            self.callback("connected", True)
            self.callback("connecting", False)
        except Exception:
            if generation == self._generation and self._client is client:
                await self.disconnect()
                self.callback("connecting", False)
                raise
            if client.is_connected:
                with contextlib.suppress(Exception):
                    await client.disconnect()

    async def disconnect(self) -> None:
        self._generation += 1
        self._ready = False
        self._pending_action = None
        self._pending_sequences.clear()
        tasks = (self._keepalive_task, self._status_task, self._writer_task,
                 self._pairing_timeout_task, self._pending_timeout_task)
        self._keepalive_task = self._status_task = self._writer_task = None
        self._pairing_timeout_task = self._pending_timeout_task = None
        current = asyncio.current_task()
        for task in tasks:
            if task and task is not current:
                task.cancel()
        for task in tasks:
            if task and task is not current:
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await task
        while not self._writes.empty():
            with contextlib.suppress(asyncio.QueueEmpty):
                self._writes.get_nowait()
        client, self._client = self._client, None
        if client and client.is_connected:
            with contextlib.suppress(Exception):
                await client.disconnect()
        self._parser.clear()
        self.callback("connected", False)
        self.callback("recording", "unknown")
        self.callback("pending", None)
        self.callback("status", "切断しました")

    async def _unexpected_disconnect(self, generation: int) -> None:
        if generation != self._generation:
            return
        self._client = None
        self._generation += 1
        self._ready = False
        self._pending_action = None
        self._pending_sequences.clear()
        for task in (self._keepalive_task, self._status_task, self._writer_task,
                     self._pairing_timeout_task, self._pending_timeout_task):
            if task and task is not asyncio.current_task():
                task.cancel()
        self.callback("connected", False)
        self.callback("recording", "unknown")
        self.callback("pending", None)
        self.callback("status", "BLE接続が切れました")

    async def record(self, start: bool) -> None:
        if not self.connected or not self._ready:
            raise RuntimeError("接続とアプリ内ペアリングが完了していません")
        action = "start" if start else "stop"
        commands = [(0x01, 0x21 if start else 0x22, b""),
                    (0x02, 0x02, b"\x01" if start else b"\x00")]
        if not start:
            commands.append((0x02, 0x7C, b"\x00"))
        self._pending_action = action
        self._pending_sequences.clear()
        if self._pending_timeout_task:
            self._pending_timeout_task.cancel()
        self._pending_timeout_task = asyncio.create_task(self._pending_timeout(self._generation, action))
        self.callback("pending", action)
        for command_set, command_id, payload in commands:
            sequence = await self._queue_command(CAMERA_ADDRESS, command_set, command_id, payload,
                                                 "録画開始" if start else "録画停止")
            self._pending_sequences[sequence] = action
        self.callback("status", "コマンド送信待ち。カメラ状態の確認を待っています")

    def _next_frame(self, destination: int, command_set: int, command_id: int,
                    payload: bytes, *, source: int = APP_ADDRESS, flags: int = 0x40,
                    sequence: int | None = None) -> tuple[int, bytes]:
        if sequence is None:
            sequence = self._sequence
            self._sequence = (self._sequence + 1) & 0xFFFF
        return sequence, frame(sequence, destination, command_set, command_id, payload,
                               source=source, flags=flags)

    async def _queue_command(self, destination: int, command_set: int, command_id: int,
                             payload: bytes, label: str) -> int:
        sequence, packet = self._next_frame(destination, command_set, command_id, payload)
        await self._writes.put((packet, label))
        return sequence

    async def _writer(self, generation: int) -> None:
        last_write = 0.0
        loop = asyncio.get_running_loop()
        try:
            while generation == self._generation:
                packet, label = await self._writes.get()
                delay = WRITE_GAP - (loop.time() - last_write)
                if delay > 0:
                    await asyncio.sleep(delay)
                client = self._client
                if generation != self._generation or not client or not client.is_connected:
                    return
                await client.write_gatt_char(WRITE_UUID, packet, response=False)
                last_write = loop.time()
                self.callback("log", f"TX {label}: {packet.hex(' ')}")
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if generation == self._generation:
                self.callback("log", f"BLE書込失敗: {exc}")
                await self.disconnect()
                self.callback("status", f"BLE書込に失敗したため切断しました: {exc}")

    async def _keepalive(self, generation: int) -> None:
        while generation == self._generation:
            await asyncio.sleep(1.0)
            await self._queue_command(SESSION_ADDRESS, 0x00, 0x2B, b"\x01\x01", "keepalive")

    async def _status_poll(self, generation: int) -> None:
        while generation == self._generation:
            await self._queue_command(CAMERA_ADDRESS, 0x02, 0x70, b"", "状態取得")
            await asyncio.sleep(2.5)

    async def _pairing_timeout(self, generation: int) -> None:
        await asyncio.sleep(30.0)
        if generation == self._generation and not self._ready:
            await self.disconnect()
            self.callback("status", "アプリ内ペアリングが30秒以内に完了しなかったため切断しました")
            self.callback("pairing", "timeout")

    async def _pending_timeout(self, generation: int, action: str) -> None:
        await asyncio.sleep(15.0)
        if generation == self._generation and self._pending_action == action:
            self._pending_action = None
            self._pending_sequences.clear()
            self.callback("pending", None)
            self.callback("confirmation_timeout", action)
            self.callback("status", "コマンドは送信しましたが、カメラ状態を確認できませんでした")

    async def _on_notification(self, generation: int, data: bytes) -> None:
        if generation != self._generation:
            return
        self.callback("log", f"RX: {data.hex(' ')}")
        prior_errors = len(self._parser.errors)
        packets = self._parser.feed(data)
        for error in self._parser.errors[prior_errors:]:
            self.callback("log", f"破損DUMLフレームを破棄: {error}")
        for packet in packets:
            await self._handle_packet(generation, packet)

    async def _handle_packet(self, generation: int, packet: Packet) -> None:
        if generation != self._generation:
            return
        if not packet.is_response and packet.flags & 0x40:
            payload = APP_DEVICE_INFO if (packet.command_set, packet.command_id) == (0x00, 0x81) else packet.payload
            _, response = self._next_frame(packet.source, packet.command_set, packet.command_id, payload,
                                           source=packet.destination, flags=0xC0,
                                           sequence=packet.sequence)
            await self._writes.put((response, f"ACK {packet.command_set:02x}/{packet.command_id:02x}"))

        if packet.command_set == 0x07 and packet.command_id == 0x45 and packet.is_response:
            if len(packet.payload) >= 2 and packet.payload[0] == 0 and packet.payload[1] == 0x01:
                await self._mark_ready(generation, "ペアリング済み")
            elif len(packet.payload) >= 2 and packet.payload[0] == 0 and packet.payload[1] == 0x02:
                self.callback("status", f"Nano画面で {PAIRING_TOKEN} を承認してください（切断も可能です）")
                self.callback("pairing", "approval_required")
            else:
                self.callback("status", "ペアリング応答を解釈できません")
        elif (packet.command_set == 0x07 and packet.command_id == 0x46
              and not packet.is_response and packet.payload[:1] == b"\x01"):
            await self._mark_ready(generation, "Nano画面で承認されました")

        if packet.is_response and packet.sequence in self._pending_sequences:
            action = self._pending_sequences.pop(packet.sequence)
            result = packet.payload[0] if packet.payload else None
            if result == 0:
                self.callback("log", f"{action}コマンドACK成功。state pushを待機")
            elif result is not None:
                self.callback("status", f"カメラがコマンドを拒否しました (0x{result:02X})")

        confirmed = recording_state_from_push(packet)
        if confirmed:
            self.callback("recording", confirmed)
            expected = "recording" if self._pending_action == "start" else "stopped"
            if self._pending_action is None or confirmed == expected:
                self._pending_action = None
                self._pending_sequences.clear()
                if self._pending_timeout_task:
                    self._pending_timeout_task.cancel()
                    self._pending_timeout_task = None
                self.callback("pending", None)
                self.callback("status", "カメラ状態で録画中を確認" if confirmed == "recording" else "カメラ状態で停止中を確認")

    async def _mark_ready(self, generation: int, message: str) -> None:
        if generation != self._generation or self._ready:
            return
        self._ready = True
        if self._pairing_timeout_task:
            self._pairing_timeout_task.cancel()
            self._pairing_timeout_task = None
        self.callback("pairing", "ready")
        self.callback("status", message)
        await self._queue_command(SESSION_INFO_ADDRESS, 0x00, 0x32,
                                  b"\x31\x31\x00\x00\x00", "セッション情報")
        await self._queue_command(CAMERA_ADDRESS, 0x02, 0x70, b"", "状態取得")
        self._status_task = asyncio.create_task(self._status_poll(generation))


APP_DEVICE_INFO = bytes([
    0x00, 0x41, 0x50, 0x50, *([0] * 37), 0x02, *([0] * 8), 0x02, 0x08, *([0] * 10)
])
