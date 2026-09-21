import asyncio

from osmo_ble_ctrl.client import NanoClient, advertisement_is_nano
from osmo_ble_ctrl.protocol import Packet


def test_advertisement_filter_excludes_other_dji_models():
    assert advertisement_is_nano("Osmo Nano-1234", {})
    assert advertisement_is_nano("DJI device", {0x08AA: b"\x19\x00"})
    assert not advertisement_is_nano("Osmo Action 5", {0x08AA: b"\x15\x00"})
    assert not advertisement_is_nano("DJI Pocket 3", {})


def test_record_generates_verified_nano_start_and_stop_sequences():
    async def run():
        events = []
        client = NanoClient("a" * 32, lambda kind, value: events.append((kind, value)))
        client._ready = True
        client._client = type("Connected", (), {"is_connected": True})()

        await client.record(True)
        start = [Packet.parse((await client._writes.get())[0]) for _ in range(2)]
        assert [(p.command_set, p.command_id, p.payload) for p in start] == [
            (0x01, 0x21, b""), (0x02, 0x02, b"\x01")
        ]
        client._pending_timeout_task.cancel()
        await client.record(False)
        stop = [Packet.parse((await client._writes.get())[0]) for _ in range(3)]
        assert [(p.command_set, p.command_id, p.payload) for p in stop] == [
            (0x01, 0x22, b""), (0x02, 0x02, b"\x00"), (0x02, 0x7C, b"\x00")
        ]
        client._pending_timeout_task.cancel()

    asyncio.run(run())


def test_stale_opposite_state_does_not_complete_pending_action():
    async def run():
        events = []
        client = NanoClient("a" * 32, lambda kind, value: events.append((kind, value)))
        client._generation = 3
        client._pending_action = "start"
        payload = bytearray(37)
        payload[4] = 0x01  # video mode, record bits clear = stopped
        await client._handle_packet(3, Packet(1, 2, 9, 0, 0x02, 0x80, bytes(payload)))
        assert client._pending_action == "start"
        assert ("recording", "stopped") in events
        assert ("pending", None) not in events

    asyncio.run(run())


def test_pairing_requires_success_status_and_positive_approval_request():
    class TrackingClient(NanoClient):
        def __init__(self):
            super().__init__("a" * 32, lambda *_: None)
            self.ready_calls = 0

        async def _mark_ready(self, generation, message):
            self.ready_calls += 1

    async def run():
        client = TrackingClient()
        client._generation = 1
        rejected_status = Packet(7, 2, 1, 0xC0, 7, 0x45, b"\x01\x01")
        rejected_approval = Packet(7, 2, 2, 0x40, 7, 0x46, b"\x00")
        approved = Packet(7, 2, 3, 0x40, 7, 0x46, b"\x01")
        await client._handle_packet(1, rejected_status)
        await client._handle_packet(1, rejected_approval)
        assert client.ready_calls == 0
        await client._handle_packet(1, approved)
        assert client.ready_calls == 1

    asyncio.run(run())


def test_writer_failure_disconnects_and_keeps_error_visible():
    class BrokenClient:
        is_connected = True

        async def write_gatt_char(self, *_args, **_kwargs):
            raise OSError("radio failed")

        async def disconnect(self):
            self.is_connected = False

    async def run():
        events = []
        client = NanoClient("a" * 32, lambda kind, value: events.append((kind, value)))
        client._generation = 1
        client._client = BrokenClient()
        await client._writes.put((b"packet", "test"))
        await client._writer(1)
        assert client._client is None
        assert events[-1] == ("status", "BLE書込に失敗したため切断しました: radio failed")
        assert ("connected", False) in events

    asyncio.run(run())


def test_pending_timeout_clears_action_and_marks_confirmation_unknown(monkeypatch):
    async def no_wait(_seconds):
        return None

    async def run():
        events = []
        client = NanoClient("a" * 32, lambda kind, value: events.append((kind, value)))
        client._generation = 4
        client._pending_action = "start"
        client._pending_sequences[1] = "start"
        monkeypatch.setattr(asyncio, "sleep", no_wait)
        await client._pending_timeout(4, "start")
        assert client._pending_action is None
        assert not client._pending_sequences
        assert ("confirmation_timeout", "start") in events

    asyncio.run(run())
