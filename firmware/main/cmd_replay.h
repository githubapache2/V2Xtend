#pragma once

/* Registers the "replay" console command (UART console only).
 *
 * Plays back a fixed set of real, previously captured, tshark-verified
 * CAM/DENM packets (see replay_data.h) through the exact same publish
 * path real sniffer hits use (usb_stream_publish_packet() /
 * bt_stream_publish_packet()). No radio transmission involved — this is
 * purely a software replay of already-legally-received data, for testing
 * the App/BLE/cache pipeline without depending on real V2X traffic being
 * in range. See CLAUDE.md roadmap for background.
 *
 * Usage:
 *   replay once               - play the sequence once, then stop
 *   replay loop [interval_ms] - play the sequence on repeat (default
 *                                interval: 1500 ms between packets)
 *   replay stop                - stop an active loop
 */
void register_replay_cmd(void);
