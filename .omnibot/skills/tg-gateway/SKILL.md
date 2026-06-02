# tg-gateway

Lightweight bidirectional Telegram Bot Gateway for Omnibot. Uses raw HTTP (`httpx`) — no heavy SDK, instant startup.

## What It Does

- Long-polling Telegram Bot API (no public IP/webhook needed)
- Bidirectional: users can message the bot → agent processes → replies back
- Streaming: progressively edits messages as the agent generates text
- Access control: whitelist of allowed Telegram user IDs
- Handles text, photos, documents, voice, media groups
- Splitting for messages > 4096 chars
- Proxy support (HTTP/SOCKS5)

## Prerequisites

1. Create a bot via [@BotFather](https://t.me/BotFather) → get a **BOT_TOKEN**
2. Get your Telegram **user ID** (from [@userinfobot](https://t.me/userinfobot))
3. Ensure `httpx` is installed: `uv sync` in skill directory

## Quick Start

```bash
# Interactive setup (creates config.json)
cd /workspace/.omnibot/skills/tg-gateway
python scripts/tgctl.py setup

# Test bot connection
tg-gateway test

# Start (via terminal session — say "start telegram gateway")
# Or: tg-gateway start (best-effort, may not persist)

# Check status / logs
tg-gateway status
tg-gateway logs
```

## How to Start (Important)

In this proot Alpine environment, background processes die when terminals exit.
The reliable way is to use a **terminal session**:

1. Say **"start telegram gateway"** to the agent
2. The agent starts a persistent terminal session with `terminal_session_start`
3. The gateway runs as a foreground process inside that session
4. The watchdog monitors every 10 minutes

## How to Stop

Say **"stop telegram gateway"** — creates a stop flag and kills the process.

## Configuration

`config.json` (created by `setup` or manually):

```json
{
  "bot_token": "123456:ABC-DEF...",
  "allow_from": ["YOUR_USER_ID"],
  "streaming": true,
  "use_markdown_v2": false,
  "proxy": "",
  "debug": false,
  "max_message_length": 4096,
  "typing_indicator": true,
  "base_url": "https://api.telegram.org"
}
```

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `TG_BOT_TOKEN` | Yes | — | Bot token from @BotFather |
| `TG_ALLOW_FROM` | Yes | — | Comma-separated Telegram user IDs |
| `TG_PROXY` | No | — | HTTP/SOCKS5 proxy URL |
| `TG_USE_MARKDOWN_V2` | No | `false` | MarkdownV2 formatting |
| `TG_STREAMING` | No | `true` | Progressive message edits |
| `TG_DEBUG` | No | `false` | Verbose logging |

## Architecture

```
Telegram User → Bot API (long-poll) → httpx → gateway.py
    → validate allow_from → extract message
    → bridge to Omnibot agent
    → agent response → format markdown → sendMessage/editMessageText
    → Telegram User sees response (streaming: progressive edits)
```

## Commands

| Command | Description |
|---|---|
| `/start` | Welcome message |
| `/help` | List available commands |
| `/status` | Gateway uptime and stats |
| `/ping` | Latency check |

## Dependencies

- `httpx[socks]>=0.27` — HTTP client with SOCKS proxy support
- Python 3.10+

## Files

```
tg-gateway/
├── SKILL.md              # This file
├── pyproject.toml        # Dependencies
├── uv.lock               # Lock file
└── scripts/
    ├── gateway.py        # Main gateway (23KB, lightweight)
    └── tgctl.py          # CLI management tool
```
