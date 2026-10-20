# Soma bots on this machine

Each folder here is one **bot**: a tab that's always there in Soma, on every device paired with this machine's runner,
with its own instructions, memory and (if it has one) a shell on this machine. Boss asks an agent to make or change
one; that's you. Read this whole file first.

## Making a bot

Make a folder named for it: lower-case letters, digits and hyphens only (`linux-setup`). In it:

**bot.json**
```json
{
  "name": "Linux setup",
  "emoji": "🐧",
  "brain": "venice",
  "model": "venice-uncensored-1-2",
  "web_search": "off",
  "machine": "omarchy-mg1",
  "tools": ["shell"]
}
```
- `name`, `emoji`: what the tab shows.
- `brain`: `venice` is the only brain bots have for now.
- `model`: a Venice text model id. List them with `venice models --type text`. A bot with tools needs a model that
  supports function calling (`venice models --type text --format json`, `capabilities.supportsFunctionCalling`).
- `web_search`: `off`, `auto` or `on`. **It must be `off` for a bot with tools**, so nothing its commands read can go
  out in a search.
- `machine`: this machine's Tailscale name, exactly (`tailscale status --self`). A folder naming another machine runs
  nothing.
- `tools`: `[]` for a bot that only talks, `["shell"]` for one that runs commands here.

**instructions.md**: Boss's words, as he gave them. Write them as he said; don't add to them or soften them. They are
sent first on every message. Change them only when Boss asks.

**notes.md** (optional): facts the bot should never lose. The bot adds lines here itself as it learns; Boss can read
them from the tab's menu. Keep it short.

The runner sees a new folder within seconds; the tab appears on Boss's devices. An edit counts from the next message.
If something is wrong, the tab says what. Tell Boss the bot is ready and what its tab is called.

## What a bot with the shell may do

- Commands that only look (`pacman -Q…`, `pacman -Ss/-Si`, `yay -Ss/-Si`, `systemctl status NAME.service
  --no-pager`, `uname -a`, `df -h`, `free -h`, `lsblk`, `lspci`, `lsmod`, `nproc`, `cat /etc/pacman.conf`,
  `cat /etc/os-release`, `cat /proc/cpuinfo`) run at once.
- Every other command is a card on Boss's phone: he allows or denies it. A card's command is one line of at most 200
  characters.
- Official packages install with `sudo soma-pacman -S --needed --noconfirm NAMES` (also `-Syu`, `-R`, `-Rs`). That is
  the only thing sudo runs, and each such card says it runs as administrator.
- AUR installs (`yay -S`, `paru -S`, makepkg) are never run by the bot: it tells Boss the exact command to run himself.
- Everything a command prints is sent to Venice.

So in instructions for a bot with the shell, don't tell it to "ask before" risky steps in words: the card is the ask.
Tell it to call `run` for each step and say why.

## Never

- Never put a key, token or password in any of these files.
- Never make `work/` a link, or link any file here to one elsewhere: the bot won't read it.
- Never set `web_search` to anything but `off` on a bot with tools.

## Removing a bot

Move its folder out of here (don't delete it unless Boss says so): its tab goes, and its conversation is kept. Put the
folder back and the same conversation comes back.

## Setting up a machine for bots (Boss does this once)

1. Soma's runner: `oma-runner install`, then pair each device (`oma-runner pair galaxy-z-fold8`).
2. Venice's key: `npm install -g veniceai-cli`, then `venice config init`.
3. For bots with the shell on Arch: install soma-pacman, root-owned, and its one sudoers line:
   `sudo install -o root -g root -m 755 soma-pacman /usr/local/bin/soma-pacman`, then
   `echo "$USER ALL=(root) NOPASSWD: /usr/local/bin/soma-pacman" | sudo tee /etc/sudoers.d/soma-pacman` and
   `sudo chmod 440 /etc/sudoers.d/soma-pacman`.
4. `mkdir -p ~/Soma/bots` and copy this file there as README.md.
