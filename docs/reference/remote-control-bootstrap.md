# OpenOmniBot private remote-control bootstrap

OpenOmniBot already serves its WebChat and MCP APIs from the Android process.
For one personal rooted device, the smallest reliable control plane is:

```text
controller phone browser
        │
        │ private LAN or Tailscale
        ▼
OpenOmniBot :8899 /webchat/
        │
        ▼
AgentRunService → VLM / tools / Android GUI execution
```

The bootstrap script starts the installed application, asks a shell/root-only
broadcast receiver to enable the existing local service, and prints a launch
URL. It does not read or rewrite MMKV. The token stays owned by
`McpServerManager` and Android Keystore-backed `TokenVault`.

## One-click script

Build and install a version containing `RemoteAccessBootstrapReceiver`, then
run from a computer with adb:

```bash
bash scripts/oob-remote-link.sh --device <serial>
```

If release and debug builds are installed side by side, select the build that
contains the receiver explicitly:

```bash
bash scripts/oob-remote-link.sh \
  --device <serial> \
  --package cn.com.omnimind.bot.debug
```

Or run the same script in Termux on the rooted target:

```bash
bash scripts/oob-remote-link.sh --share
```

When Tailscale is active, its `100.64.0.0/10` address is preferred. Otherwise
the script returns a private Wi-Fi/LAN address. Override selection when needed:

```bash
bash scripts/oob-remote-link.sh --host 100.80.12.34
```

Rotate a possibly exposed token explicitly; rotation invalidates previous
links and WebChat sessions:

```bash
bash scripts/oob-remote-link.sh --refresh-token
```

The URL uses `#token=...`, not `?token=...`. URL fragments are not included in
HTTP requests, server access logs, or HTTP Referer headers. WebChat exchanges
the fragment token for its session cookie and removes it from the address bar.

The complete link remains a credential. Do not use a public URL shortener or
online QR-code generator. Never expose port 8899 with router port forwarding or
Tailscale Funnel.

## Related open-source systems

### DeviceFarmer STF and VK DeviceHub

[DeviceFarmer STF](https://github.com/DeviceFarmer/stf) and
[VK DeviceHub](https://github.com/VKCOM/devicehub) provide browser-based live
screen control, device inventory, ADB integration, and multi-user device-lab
management. DeviceHub reports 30–40 FPS viewing and supplies a REST API. These
systems are a good reference for a future fleet console, but require a host
ADB server and several backend services. They are too heavy for a one-phone
OpenOmniBot bootstrap.

### scrcpy and browser ports

[scrcpy](https://github.com/Genymobile/scrcpy) is the strongest reference for
low-latency Android video and input transport. The official clients target
desktop platforms and communicate with a device-side server over ADB. Projects
such as [ws-scrcpy](https://github.com/NetrisTV/ws-scrcpy) demonstrate a web
client, but its own documentation warns that the device WebSocket path is not
encrypted. This belongs behind a private tunnel and is best treated as an
optional manual-takeover channel, not the Agent command channel.

### WebADB

[ya-webadb](https://github.com/yume-chan/ya-webadb) implements ADB protocols in
TypeScript and is useful for browser-based debugging. WebUSB pairing and ADB
authorization make it less suitable for unattended phone-to-phone control,
and exposing ADB would grant a much broader capability than WebChat.

### Mobile GUI agents

[AppAgent](https://appagent-official.github.io/) and
[Mobile-Agent](https://github.com/X-PLUG/MobileAgent) are relevant research and
open-source references for visual observation, grounding, planning, and action
execution on mobile UIs. They inform the Agent layer, but do not replace the
authenticated remote transport or lifecycle management implemented here.

### Tailscale

Tailscale supplies encrypted network reachability and stable device addresses;
it does not start the destination web service. OpenOmniBot therefore continues
to own port 8899 and authentication, while Tailscale carries the traffic across
networks. Restrict access with tailnet grants/ACLs when the tailnet includes
other users or devices.

## Production follow-ups

- Move the Ktor WebChat server into an Android foreground service so it remains
  alive when the activity process is backgrounded by an aggressive OEM.
- Add an in-app QR/share panel so ordinary non-root users can bootstrap without
  adb or Termux.
- Add a separate, explicitly enabled scrcpy/WebRTC manual-takeover channel.
- Keep Agent commands and manual screen control independently authorized and
  independently revocable.
