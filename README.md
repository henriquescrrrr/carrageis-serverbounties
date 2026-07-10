# ServerBounties

A comprehensive bounty and hitman contract system for Paper 1.21+ servers with proportional rewards, economy integration, and full GUI-based interactions.

## Features

### Bounty System
- **Any player** can contribute to a bounty on another player via GUI
- Multiple contributions **stack** — each is a separate entry
- **Proportional distribution** — when a bounty target is killed, the bounty is split among all attackers based on damage dealt
- **Tax system** — configurable tax rate on bounty placement
- **Anti-exploit** — suspicious death detection with deathchest coordinate reveal
- **Combat-tag aware** — contributions can't be cancelled while target is in combat
- **Faction-aware** — can't bounty faction members (configurable)

### Hitman Contracts
- **Anonymous contracts** on specific targets with full negotiation system
- Players can hire available hitmen via GUI with counter-offers
- Only completes if the hired hitman kills the target
- Auto-refund if someone else kills the target
- **Hitman killed by target** — partial refund to client, reward to target
- **Contract expiry** — full refund to client when timer runs out
- **Rescission negotiation** — client and hitman can negotiate early cancellation with custom split
- **BossBar warning** for contract targets (separate from bounty BossBar)
- **Anonymous broadcast** when a contract is accepted
- **Hitman settings** — availability, min price, hours range, max concurrent contracts
- **Faction warning** — warns client if hitman is in the same faction as target

### Death Decision Tree
When a player dies:
1. **Determine killers** via CombatLOG combat tag + damage history
2. **Validate killers** — remove self-damage and same-faction
3. **Distribute bounty** proportionally by damage dealt
4. **Process hitman contracts** — complete if hitman killed target, refund if someone else did
5. **Hitman killed by target** — if dead player was a hitman, check if their contract target killed them
6. **Suspicious death** tracking if no valid killers found

### Economy
- **MultiBank** primary (fully async, reflection-based)
- **Vault** fallback (wrapped in async)
- Never blocks the main thread

### Localization
- Per-player language via **PlayerSettings API**
- Fallback to config global language
- Ships with **English**, **Portuguese**, and **French**
- MiniMessage formatting throughout

---

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/bounty` | Open the Place Bounty GUI | `serverbounties.bounty` |
| `/bounty board` | Open the Bounty Board | `serverbounties.bounty` |
| `/bounty check <player>` | View bounty details for a player | `serverbounties.bounty` |
| `/bounty my` | View your own contributions | `serverbounties.bounty` |
| `/bounty top` | Show top 10 bounties in chat | `serverbounties.bounty` |
| `/bounty remove <player>` | Admin: remove all bounties (with refund) | `serverbounties.admin` |
| `/bounty reload` | Admin: reload config and lang files | `serverbounties.admin` |

**Alias:** `/bounties` → same as `/bounty board`

## Permissions

| Permission | Description | Default |
|-----------|-------------|---------|
| `serverbounties.bounty` | Place bounties + view board | `true` |
| `serverbounties.admin` | Admin commands (remove, reload) | `op` |

---

## GUIs

All bounty interactions are GUI-based:

- **Bounty Board** — Paginated list of all active bounties sorted by total amount. Player heads with bounty info. Click to view details.
- **Bounty Detail** — Shows all contributors for a specific target. Each entry with contributor head, amount, and date.
- **Place Bounty** — Two-phase flow: select target (online players, filtered by self/faction) → adjust amount with ±buttons (shift = ×10) → confirm with tax breakdown.
- **My Bounties** — Shows all bounties the player has contributed to. Click to cancel and get refunded. Blocked if target is in combat.

---

## Configuration

### config.yml

```yaml
language: en_US
debug: false

economy:
  mode: auto
  priority: ["MultiBank", "Vault"]

bounty:
  min-amount: 500
  max-amount-per-contribution: 1000000
  max-total-per-target: 10000000
  tax-rate: 0.10
  allow-offline-targets: false
  broadcast-threshold: 5000
  allow-same-faction: false
  cancel-blocked-in-combat: true

damage-tracking:
  valid-kill-window-seconds: 300
  suicide-window-seconds: 150
  hit-notification-cooldown-seconds: 10

anti-exploit:
  suspicious-death-threshold: 7
  suspicious-death-window-minutes: 60
  reveal-deathchest-coords: true
```

### Language Files

Located in `plugins/ServerBounties/lang/`:
- `en_US.yml` — English
- `pt_PT.yml` — Portuguese
- `fr_FR.yml` — French

All messages use MiniMessage format with `{placeholder}` replacements.

---

## Dependencies

### Required (one of):
- **MultiBank** — Primary economy provider (async, cents-based)
- **Vault** — Fallback economy provider

### Optional:
- **PlayerSettings** — Per-player language selection
- **CombatLOG** — Combat tag detection for death processing and cancel blocking
- **ServerFactions** — Faction membership checks

All hooks are reflection-based — the plugin works without any optional dependency.

---

## Technical Details

- **Paper 1.21+** with Adventure API (MiniMessage)
- **Java 21**
- **Brigadier commands** via Paper lifecycle events
- **paper-plugin.yml** descriptor
- **SQLite** database for persistent storage (WAL mode)
- **In-memory cache** for all bounty reads (O(1) in DeathListener)
- **DamageTracker** — in-memory PvP damage history with configurable time windows
- **100% async economy** — never calls `.join()` or `.get()` on futures
- All DB operations run on a dedicated single-thread executor
- Cleanup tasks run every 60 seconds for old damage records and expired cooldowns

---

## Building

```bash
./gradlew build
```

The output jar will be in `build/libs/ServerBounties-1.0.0.jar`.

---

## License

All rights reserved.

