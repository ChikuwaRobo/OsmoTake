import pytest

from osmo_ble_ctrl.protocol import FrameParser, Packet, frame, pairing_payload, recording_state_from_push


def test_frame_matches_upstream_regression_vector_and_round_trips():
    data = frame(0x1234, 0x1C, 0x53, 0x10, b"\x00\x00\x00\x00")
    assert data == bytes.fromhex("55 11 04 92 02 1c 12 34 40 53 10 00 00 00 00 88 2e")
    packet = Packet.parse(data)
    assert packet.sequence == 0x1234
    assert packet.payload == b"\x00\x00\x00\x00"


def test_parser_reassembles_split_and_combined_notifications():
    first = frame(1, 1, 2, 0x70)
    second = frame(2, 7, 7, 0x45, b"\x00\x02", flags=0xC0)
    parser = FrameParser()
    assert parser.feed(first[:7]) == []
    packets = parser.feed(first[7:] + second)
    assert [(p.sequence, p.command_id) for p in packets] == [(1, 0x70), (2, 0x45)]


def test_parser_rejects_bad_header_and_packet_crc_then_resynchronizes():
    bad_header = bytearray(frame(1, 1, 2, 3))
    bad_header[3] ^= 1
    bad_packet = bytearray(frame(2, 1, 2, 4))
    bad_packet[-1] ^= 1
    good = frame(3, 1, 2, 5)
    parser = FrameParser()
    packets = parser.feed(bytes(bad_header + bad_packet + good))
    assert [p.sequence for p in packets] == [3]
    assert any("CRC" in error for error in parser.errors)


def test_packet_parse_rejects_tampered_crc():
    data = bytearray(frame(4, 1, 2, 0x70))
    data[9] ^= 1
    with pytest.raises(ValueError, match="CRC"):
        Packet.parse(bytes(data))


def test_pairing_payload_keeps_stable_identifier_shape_and_visible_token():
    payload = pairing_payload("a" * 32, "OSMOCTRL")
    assert payload[:2] == b"\x20a"
    assert payload[33:] == b"\x08OSMOCTRL"


def _state_packet(payload: bytes, *, response: bool = False) -> Packet:
    return Packet(1, 2, 10, 0xC0 if response else 0x00, 0x02, 0x80, payload)


def test_only_authoritative_full_state_confirms_recording():
    payload = bytearray(37)
    payload[0:4] = (0x40).to_bytes(4, "little")
    payload[4] = 0x01
    assert recording_state_from_push(_state_packet(bytes(payload))) == "recording"
    assert recording_state_from_push(_state_packet(bytes(payload), response=True)) is None


def test_compact_dock_power_bit_is_not_mistaken_for_recording():
    payload = bytearray(34)
    payload[27:29] = (0x40).to_bytes(2, "little")
    payload[29:33] = (1).to_bytes(4, "big")
    payload[33] = 0x01
    assert recording_state_from_push(_state_packet(bytes(payload))) is None


def test_unknown_full_mode_is_not_mistaken_for_stopped():
    payload = bytearray(37)
    payload[4] = 0xFF
    assert recording_state_from_push(_state_packet(bytes(payload))) is None
