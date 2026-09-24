# MQTT Call Management and Scroll-Free Call Screen

## Goal

Keep the active call screen usable without scrolling while moving broker,
channel, and key administration into a separate management page. The app
continues to use one MQTT broker connection at a time, automatically
reconnecting on startup to the last-used broker, numeric channel, and active
key. LAN broker discovery is available from broker management.

## User experience

### Main call page

The main page is portrait and scroll-free. It contains:

- a top-right Exit action;
- the large PTT button at the top;
- a broker dropdown showing saved/default brokers;
- a channel dropdown showing saved numeric channels;
- an active-key dropdown showing the existing key slots;
- Connect/Disconnect and connection/audio status.

The page does not contain broker forms, channel editors, or full key editors.
Selecting a dropdown value updates the pending call configuration and persists
the selection. Microphone access remains gated by a PTT press; startup,
reconnect, and management screens never open or request the microphone.

### Management page

A separate in-app page provides three management sections:

- Brokers: list, add, edit, delete, select, and Scan LAN.
- Channels: list, add, delete, select, and Generate random channel.
- Keys: expose the existing three encrypted key slots for edit, generation,
  activation, and deletion/clearing.

The existing saved-key storage remains three slots for compatibility with
installed data. Channel storage is a separate persisted collection, with the
last-used channel selected by default.

### LAN scan

Scan LAN uses the active Wi-Fi IPv4 network prefix and probes TCP port 1883
with short connection timeouts and bounded concurrency. It displays reachable
addresses as candidates; the user chooses which candidate to save. A saved LAN
broker is non-TLS on port 1883 and uses 100 ms audio packets. Discovery never
auto-connects and never scans arbitrary Internet ranges.

If no usable Wi-Fi IPv4 route exists, the UI reports that LAN scanning is
unavailable. Scan cancellation and timeout must return control to the page.

## Startup reconnect

After the service is started and bound, it loads the persisted last-used
broker, channel, and active key. If a prior connection was intentionally
active, it reconnects automatically. It must not request microphone permission
or start capture. Explicit Disconnect and Exit clear the reconnect intent;
ordinary Activity unbinding/backgrounding does not.

## Channel contract

Channels are numeric strings containing 1–16 ASCII digits. The topic remains
`call/channel/<channel>`. Random generation uses a cryptographically secure
random source, generates a 16-digit value whose first digit is non-zero, adds
it to the saved channel collection, and selects it. Existing 1–12 digit
channels remain valid. Android and Linux validation/parsing must accept the
same 1–16 digit contract.

## Non-goals

- Simultaneous LAN and WAN MQTT connections.
- Automatic MQTT authentication against every scanned port.
- Background microphone capture.
- Removing the existing three-key compatibility model.

## Acceptance criteria

1. Main call page shows PTT, broker/channel/key selectors, status, and Exit
   without vertical scrolling.
2. Broker/channel/key management works on a separate page and persists across
   app restarts.
3. Startup reconnect uses the last active tuple only when reconnect intent was
   previously enabled; it never opens the microphone.
4. LAN scan finds reachable port-1883 hosts on the active local subnet and
   saves only user-selected results.
5. Numeric channels of 1–16 digits work end-to-end on Android and Linux;
   invalid characters and longer values are rejected.
6. Existing PTT, permission, orientation, background audio, packet interval,
   gain, and Exit behavior remain intact.
