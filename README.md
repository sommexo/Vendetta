# VenMark — Vendetta SMP Plugin

VenMark is a Paper plugin for the Vendetta SMP server. This README describes the implementation in the merged repository at the time of writing. It is not a claim of live-server verification.

## Contents

- [Requirements and build](#requirements-and-build)
- [Installation and data](#installation-and-data)
- [Commands](#commands)
- [Permissions](#permissions)
- [Marks and economy](#marks-and-economy)
- [Classes and relics](#classes-and-relics)
- [Abilities](#abilities)
- [Angel encounters](#angel-encounters)
- [Quests and caches](#quests-and-caches)
- [Scoreboards and player interfaces](#scoreboards-and-player-interfaces)
- [Configuration](#configuration)
- [Persistence and lifecycle](#persistence-and-lifecycle)
- [Troubleshooting and verification limits](#troubleshooting-and-verification-limits)
- [Current implementation notes](#current-implementation-notes)

## Requirements and build

The Maven project compiles for Java 25 and declares the Paper API version range `[26.3.build,)` from the Paper Maven repository. The plugin metadata declares `api-version: '26.3'` and loads at startup. These are the project's configured targets; they do not establish compatibility with every server build in that range.

Build and run the test suite with:

```text
mvn clean package
```

The JAR is written to `target/vendetta-release1.1.jar`. Paper API is a provided dependency and is not bundled as a plugin library. Maven also runs the configured Shade plugin. The project includes JUnit 5 tests for pure rules and helper classes; these tests are not a substitute for running a Paper server.

## Installation and data

1. Use a Paper server compatible with the configured API and Java 25.
2. Copy `target/vendetta-release1.1.jar` into that server's `plugins` directory.
3. Start the server. Paper loads the plugin named **VenMark** and creates its data directory and default `config.yml`.
4. Back up the plugin data directory before replacing the JAR or editing persistent state.

The plugin registers its commands in Java during `onEnable`; `paper-plugin.yml` contains no command declarations or command aliases. It registers listeners and encounter services during startup. A scheduled Angel wave starts shortly after startup, and the regular wave timer then follows the configured interval.

## Commands

Paper registers these eight root commands: `/marks`, `/mark`, `/vanguard`, `/venmark`, `/vendetta`, `/deposit`, `/withdraw`, and `/bounty`. No aliases are declared in `paper-plugin.yml` or passed to the registration calls. Subcommands and accepted ability-name spellings are described below.

### Player commands

| Syntax | Access and behavior |
|---|---|
| `/marks` | Requires `venmark.use`. For a player, displays their bank balance. With no arguments from console, prints usage. |
| `/marks top` | Requires `venmark.use`. Displays up to ten recorded balances as “The Ledger.” This list is sorted by balance descending. |
| `/mark ally <player>` | Requires `venmark.use`; player-only. Adds a previously seen or online player's UUID to the sender's ally list. Self-alliance is rejected. |
| `/mark unally <player>` | Requires `venmark.use`; player-only. Removes that UUID from the sender's ally list. |
| `/mark bounties` | Requires `venmark.use`. Lists active diamond, Nether Star, and banked-Mark bounty records and identifies the highest-valued target using configured comparison weights. |
| `/mark bounty <player> <diamonds\|stars\|netherstars> <amount>` | Requires `venmark.use`; player-only. Consumes the specified number of ordinary diamonds or Nether Stars and adds them to a target's item bounty. `stars` and `netherstars` both select Nether Stars. The target must have joined before, cannot be the sender, and the amount must be a positive whole number. The per-type target limit is configured by `bounties.max-items-per-type-per-target`. |
| `/deposit <amount\|hand\|offhand>` | Requires `venmark.use`; player-only. Deposits genuine VenMark currency into the bank. Numeric amounts must be positive multiples of 10. `hand` and `offhand` deposit the genuine currency stack in the selected hand, subject to bank capacity. |
| `/withdraw <amount>` | Requires `venmark.use`; player-only. Withdraws genuine currency for a positive multiple of 10 Marks. A 5% fee rounded up to the next whole Mark is added to the bank deduction. The inventory must have room. |
| `/bounty <player>` | Requires `venmark.use`; player-only. Posts all authentic physical VenMark currency carried by the sender as a bounty on an online, non-self target. |
| `/venmark` | Shows a short command guide; the no-argument help path has no permission check. |
| `/venmark choose <vanguard\|phantom\|warden>` | Requires `venmark.use`; player-only. Opens the class selection path and selects a class if the player has none or class changing is enabled. A selected class is otherwise locked. |
| `/venmark deposit <amount>` | Requires `venmark.use`; player-only. Same bank operation as numeric `/deposit`; positive multiple of 10, using authentic physical currency. |
| `/venmark withdraw <amount>` | Requires `venmark.use`; player-only. Same withdrawal and fee rules as `/withdraw`. |
| `/venmark ability` | Does not require a permission node in the handler. A player sees the names and current tiers for their selected class. Console sees the supported ability names. |
| `/venmark ability <ability name>` | Does not require a permission node in the handler. Displays the description text implemented by the command for that ability and, for players, their current tier when available. Names may contain spaces or underscores; the parser normalizes spaces and underscores to hyphens. See [ability names](#ability-command-names). |
| `/vanguard status` | Requires `venmark.use`; player-only. If the player is Vanguard, shows Berserker, Bloodthirst, ultimate progression, and remaining ultimate cooldown. Otherwise reports that Vanguard is not selected. |
| `/vanguard choose` | Requires `venmark.use`; player-only. Does not select a class; directs players to `/venmark choose`. |
| `/vanguard` or another `/vanguard` argument | Requires `venmark.use`; player-only. Shows the Vanguard usage hint. |

**Argument validation:** amount arguments are parsed as positive whole numbers. Player names are resolved online first, then through the server's offline-player data where the implementation permits it. Bounty targets must be online for `/bounty`; `/mark bounty` accepts a previously joined offline target. Command suggestions are convenience only and do not replace validation.

### Administrative commands

All `/vendetta` commands require `venmark.admin`. `/vendetta` accepts both players and console senders.

| Syntax | Permission | Behavior and validation |
|---|---|---|
| `/vendetta settings opCooldownBypass <true\|false>` | `venmark.admin` | Saves the operator-only Vanguard cooldown bypass setting. It affects Vanguard cooldown checks only and does not bypass active-ability state rules. |
| `/vendetta settings classChanger <true\|false>` | `venmark.admin` | Saves whether players may change a previously selected class using `/venmark choose`. |
| `/vendetta settings triggerableAngelEscapes <true\|false>` | `venmark.admin` | Saves whether manual Angel Escape activation is enabled. Defaults to false. |
| `/vendetta angelEscape` | `venmark.admin` | Starts a manual Angel wave only when `triggerableAngelEscapes` is enabled. Reports the actual number spawned and their world and coordinates; if no wave starts, it reports failure. |
| `/marks give <player> <amount>` | `venmark.admin` | Gives a positive whole number of banked Marks to a previously joined or online player. |
| `/marks take <player> <amount>` | `venmark.admin` | Removes up to the requested amount from a balance, flooring the result at zero. |
| `/marks check <player>` | `venmark.admin` | Displays the target's UUID and current banked balance. |
| `/marks inspect <player>` | `venmark.admin` | Same lookup behavior as `/marks check`. |
| `/marks reload` | `venmark.admin` | Reloads Bukkit's plugin configuration from `config.yml`. |
| `/venmark marks give <player> <amount>` | `venmark.marks.give` or `venmark.admin` | Grants a positive whole number of banked Marks. |
| `/venmark marks reset <player>` | `venmark.marks.reset` or `venmark.admin` | Resets the target's banked Marks to zero and writes an audit record. |
| `/venmark marks abuse` | `venmark.admin` | Displays the most recent suspected-abuse and audit records, up to 20. |
| `/venmark currency give <player> <dye\|star> <1-64>` | `venmark.relic.give` or `venmark.admin` | Gives authentic physical currency to an online player. `dye`/`purple-dye` means 10 Marks per purple dye; `star`/`nether-star` means 1,000 per Nether Star. Inventory overflow is dropped at the recipient. |
| `/venmark relic give <class> <ascendant\|imperfect\|perfect> <player>` | `venmark.relic.give` or `venmark.admin` | Creates and delivers a genuine relic for an online recipient. The command selects the class's generic ability-choice relic for Imperfect/Perfect, or its ultimate relic for Ascendant. |
| `/venmark relic give <player> <class> <ability> <tier-1-3>` | `venmark.relic.give` or `venmark.admin` | Legacy argument order retained by the handler. Tier accepts `1`, `2`, `3`, or tier names. The class, ability, and tier combination must pass the relic definition validator. |
| `/venmark class set <player> <vanguard\|phantom\|warden>` | `venmark.class.admin` or `venmark.admin` | Sets or overrides a player's class selection. |

Admin grant commands validate the recipient and item definition. The relic grant requires inventory space; it does not drop the relic on overflow. Admin actions handled by the code are written to `economy-abuse.log` where applicable.

The command handlers validate exact argument counts. Invalid syntax and invalid setting values return usage or error messages. `/venmark abuse` reaches an unimplemented dispatch path and returns an invalid-syntax message; use `/venmark marks abuse` instead. Suggestions expose only a subset of supported inputs and may not list every accepted synonym or legacy form.

### Ability command names

`/venmark ability` lists these twelve descriptions: `berserker`, `bloodthirst`, `blood-blood-blood`, `grim-reaper`, `predators-instinct`, `marked-prey`, `into-the-void`, `afterimage`, `unshaken`, `sculk-pulse`, `ancient-guardian`, and `unyielding`.

The parser also accepts normalized variants: `bloodbloodblood` and `blood_blood_blood`; `grimreaper` and `grim_reaper`; `predators_instinct`; `marked_prey`; `intothevoid` and `into_the_void`; `sculk_pulse`; and `ancient_guardian`. The names correspond to command labels, not necessarily separate progression records. In particular, **Afterimage** and **Unyielding** are Ascendant bonuses rather than independently upgradeable relics.

## Permissions

The plugin registers these permission nodes:

| Permission | Default | Use |
|---|---|---|
| `venmark.use` | True | Player economy, class, status, and root commands that require the player node. |
| `venmark.admin` | Operator | `/vendetta` settings and manual event commands; legacy `/marks` administration; `/venmark marks abuse`; override for the scoped `/venmark` admin permissions. |
| `venmark.marks.give` | Operator | `/venmark marks give`. |
| `venmark.relic.give` | Operator | `/venmark currency give` and `/venmark relic give`. |
| `venmark.class.admin` | Operator | `/venmark class set`. |
| `venmark.marks.reset` | Operator | `/venmark marks reset`. |

The registered Paper command permission for `/marks`, `/mark`, `/deposit`, `/withdraw`, `/bounty`, and `/vanguard` is `venmark.use`. `/venmark` and `/vendetta` perform their own permission checks in their command handlers. The `/venmark ability` path has no explicit permission-node check after root dispatch. Scoped admin subcommands accept either their listed permission or `venmark.admin`.

## Marks and economy

### Banked and physical Marks

- The authoritative bank balance is UUID-keyed in `balances.yml`.
- A genuine purple dye is worth 10 Marks; a genuine Nether Star is worth 1,000 Marks. PDC tags, material, and denomination are validated; display names and enchant glint alone do not authenticate currency.
- The bank cap is 20,000 Marks. Normal credits above the cap are recorded as pending credit in `pending-marks.yml`.
- Deposits require genuine currency and available bank capacity. Right-clicking a genuine currency item also attempts to deposit it. The `/deposit` commands are manual alternatives.
- Withdrawals create genuine currency and charge an additional 5% fee rounded up to a whole Mark.
- Ordinary purchases and debit helpers reject insufficient balances. The specific Wanderer failure penalty is allowed to create a negative balance; a player who dies while their bank balance is negative is permanently banned by the plugin.
- Marks balances are saved on successful transactions, periodically every ten minutes, when a player leaves, and during plugin shutdown.

### Earning and PvP settlement

Configured mob lists determine mob rewards. The defaults are 300 Marks for listed bosses, 10 for listed hostiles, and 5 for listed passives. A player or player-shot projectile must be the mob's qualifying final attacker. Unlisted types pay zero.

PvP death settlement requires a recorded player attacker and a qualifying final damage source (direct player damage, player-shot projectile, or supported status damage attribution). The victim loses 25% of their nonnegative bank balance, rounded down to whole Marks; the transfer to the killer is capped at 2,500 Marks, and any excess victim loss is not transferred. The configured base reward is also credited. Eligible killer-victim pairs have a 600-second repeat-kill block. Each killer is limited to three full Mark settlements per local calendar day; after that limit, the plugin does not apply the victim loss, transfer, or base reward. Overflow above the bank cap is stored as pending credit.

When killed by a non-player living attacker or its projectile, a player drops 25% of carried authentic physical currency, rounded down to a multiple of 10 Marks. The remaining currency is represented as owner-locked return drops. This is separate from their bank balance and the server's handling of unrelated inventory drops.

### Bounties and allies

There are two player-facing bounty paths:

- `/bounty <player>` stores the sender's authentic carried currency against an online target. At a qualifying target death, stored physical currency drops, and a nonempty bounty also creates 250 Marks of genuine purple-dye currency.
- `/mark bounty <player> <diamonds|stars> <amount>` consumes ordinary diamond or Nether Star items and tracks those item counts. `/mark bounties` shows current targets. The legacy `/mark bounties` comparator values are configured under `bounties.value`.

`/mark ally` and `/mark unally` maintain UUID-keyed one-way ally records in `allies.yml`. Some team effects check either player's ally relation; others enumerate the activator's ally list. Ally entries are used by Vanguard and Warden abilities as described below.

### Leaderboards

- `/marks top` lists up to ten recorded bank balances.
- The existing right-side sidebar displays the viewer's current balance and a top-five leaderboard derived from the same in-memory authoritative balance map, including balances for offline UUIDs. Names come from online or offline player records; unknown names use an `Unknown-<UUID prefix>` fallback. Ties are resolved deterministically by normalized name, name, and UUID.
- Balance-changing code refreshes online sidebars. The scoreboard is rebuilt per player and also conditionally includes Ancient Guardian details for its active caster.

## Classes and relics

### Class selection

The first join opens a three-option class menu: Vanguard, Phantom, or Warden. Selection is saved by UUID in `player-classes.yml`; the class is locked unless `settings.classChanger` is enabled or an administrator uses `/venmark class set`. Previous Vanguard records are treated as Vanguard selection when no separate class entry exists.

Vanguard players cannot keep or craft a mace through the class inventory restriction listener: Maces are ejected from their inventory on relevant inventory actions, and crafting or pickup is blocked. Other classes are not subject to that specific rule.

### Relic tiers and acquisition

The three relic tiers are Imperfect, Perfect, and Ascendant. Genuine relics are single-stack items identified with PDC fields for class, ability, tier, format, tier ID, and unique relic ID; the issued/consumed identity ledger is persisted in `relic-identities.yml`.

- **Imperfect** relics use an Echo Shard item and can upgrade an eligible regular class ability from Dormant.
- **Perfect** relics use a Diamond item and can upgrade an eligible regular ability or ultimate to Perfect.
- **Ascendant** relics use a Totem of Undying item and apply only to an ultimate already at Perfect.
- A generic class relic opens the ability menu. A class-specific valid relic can apply through its corresponding ability flow.
- Relics from another class, invalid or duplicate identities, consumed identities, and unsupported class/ability/tier combinations are rejected.

Acquisition paths in the current implementation:

1. Angel trade offers use banked Marks. Tier prices default to 1,000 Marks for tier 1 and 2,000 Marks for tiers 2 and 3. Ascendant offers also require one matching Netherite item.
2. Right-clicking a previously unopened container has a 0.2% chance to issue a **Vanguard** relic. If one drops, the tier roll has a 5% chance to be Perfect and otherwise is Imperfect. Each container location is marked opened before the roll. This exploration path does not currently issue Phantom or Warden relics.
3. Wanderer caches guarantee a class-matched Perfect relic and may include a separate Ascendant relic roll.
4. Crazy quest completion can award Perfect or Ascendant relics from the quest reward pool.
5. Administrators can issue genuine relics through `/venmark relic give`.

### Shatter Sword

The Shatter Sword is a custom, almost-broken Netherite Sword with a unique persistent ID. An unpaid sword costs 500 banked Marks on first acquisition; the paid ID follows that sword through transfer, so a recipient is not charged again. Wanderer-cache Shatter Swords are registered prepaid and do not incur an extra acquisition charge. Angel trades that award a Shatter Sword include the 500-Mark acquisition amount in the trade transaction.

Only a valid main-hand player-versus-player melee hit with a paid, unused Shatter Sword can trigger its effect. The hit retains its normal damage. The target receives a lightning visual only; it does not take lightning damage or catch fire. The plugin selects one of the target's highest-tier eligible, non-disabled relics, choosing randomly among ties, and disables that relic for 30 minutes. The sword is consumed only if an eligible relic is disabled. If none exists, the visual still occurs and the sword is retained. A used sword ID cannot trigger again. Used IDs persist across restarts, but active relic-disable effects are cleared on restart.

Paper resource commands, loot tables, or other plugins may create items that resemble relics, but only items accepted by VenMark's identity checks are functional VenMark relics.

## Abilities

Progression tiers are Dormant, Imperfect, Perfect, and Ascendant. Which tiers are available depends on the ability and eligible relic upgrades. Ability descriptions from `/venmark ability` are command text; this section summarizes the current event implementation.

### Vanguard

| Ability | Trigger and implemented effect |
|---|---|
| **Berserker** (passive) | For qualifying outgoing player damage with a sword or axe while below 20 health, applies the tier multiplier: Dormant 1.10×, Imperfect 1.25×, Perfect 1.50×. Perfect becomes 1.75× below 10 health. Ascendant has no separate Berserker upgrade and the helper returns the base multiplier for that tier. Maces are not eligible Vanguard weapons. |
| **Bloodthirst** (counter) | Right-click with a main-hand sword arms a 750 ms Dormant window or a 1,000 ms Imperfect window. If the Vanguard is sneaking, still holding a sword, and receives an eligible direct entity hit within the window, the incoming hit is not cancelled and the player is healed to their max-health attribute on the next tick. Cooldown is 30 seconds. The handler checks eligible direct entity damage, whose attacker resolution can include players and living entities. |
| **BLOOD BLOOD BLOOD** (ultimate) | A successful Totem of Undying pop activates the ultimate when ready. It refreshes Strength and Speed effects while combat continues and ends after 15 seconds without qualifying combat damage. Dormant cooldown is 240 seconds; Imperfect/Perfect use 480 seconds; Ascendant uses 2,400 seconds. Dormant grants configured Strength I and Speed I. Upgraded tiers grant configured Strength II and Speed II plus configurable Fire Resistance; Ascendant Strength scales with its tracked kill streak up to the configured cap. Perfect and Ascendant also give listed nearby allies Strength and Speed for six seconds within 16 blocks by default. A second Totem pop while active ends it. Natural healing is blocked only for active non-Dormant ultimates; food is blocked for active Imperfect specifically. |
| **Grim Reaper** (passive) | Each qualifying player kill as Vanguard increments persistent kill-based Strength, capped at Strength IV. It persists after the ultimate ends and is cleared on death. Eligible final damage may be direct, a player-shot projectile, or supported attributed status damage. |

Bloodthirst's current event checks and heal timing are implementation details; the command description says a failed counter consumes the cooldown, while the listener starts its cooldown only after a qualifying trigger. Treat that command wording as inconsistent with the event path.

### Phantom

| Ability | Trigger and implemented effect |
|---|---|
| **Predator's Instinct** (counter) | Right-clicking with a main-hand sword arms a one-second window, subject to its 120-second cooldown. The next qualifying direct entity hit from a player or `Monster` against the Phantom is cancelled and consumes the activation; the current listener does not require the Phantom to be sneaking. Dormant teleports the Phantom behind the attacker and stuns for 0.5 seconds without blindness. Imperfect stuns for 3 seconds and applies blindness; a main-hand mace launches the attacker upward instead of teleporting. Perfect/Ascendant without a mace teleport behind the attacker with no blindness and a 2.5-second stun; with a mace, the attacker is launched and blinded/stunned for 5 seconds, and the Phantom receives two seconds of Slow Falling followed by a downward velocity adjustment. Safe-position checks are used for teleport. |
| **Marked Prey** (passive) | A player who attacks the Phantom is given Glowing for 6 seconds at Dormant, 9 at Imperfect, and 15 at Perfect/Ascendant. Attacker resolution includes supported direct and projectile paths. |
| **Into the Void** (ultimate) | Left-clicking with an Ender Pearl cancels the throw and activates a five-minute cooldown. The Phantom receives refreshed Invisibility and Speed I for 5 seconds Dormant, 15 Imperfect, or 25 Perfect/Ascendant. Dealing a hit applies a tier bonus to that damage (none, 1.10×, 1.25×, or 1.50× respectively) and ends the effect; taking a hit also ends it. Clicking again ends it manually. At Ascendant, natural duration end without a hit grants Speed IV for 15 seconds. |
| **Afterimage** (Ascendant bonus) | The `afterimage` ability description says Speed III for 12 seconds after a successful Predator's Instinct counter while Into the Void is Ascendant. The current Predator's Instinct event implementation instead produces a short Cloud particle trail when the Predator's Instinct tier or the `afterimage` progression tier is Ascendant; it does not grant Speed III there. The event and description therefore disagree. |

The Ascendant Predator's Instinct particle trail is visual only; it is not a decoy entity or a functional target diversion. Stuns restore movement restrictions through timed state and cleanup paths. Temporary active Void, stun, and cooldown state does not all share the same persistence rules; see [persistence](#persistence-and-lifecycle).

### Warden

| Ability | Trigger and implemented effect |
|---|---|
| **Unshaken** (passive) | Sets the Warden's base knockback-resistance attribute to 20% Dormant, 30% Imperfect, and 40% Perfect/Ascendant while Warden is selected. |
| **Sculk Pulse** (active) | Right-clicking with a main-hand sword reveals non-ally enemy players within 20 blocks by applying Glowing for three seconds. Cooldown is 20 seconds. |
| **Ancient Guardian** (ultimate) | Left-clicking with a main-hand shield activates a 15-minute cooldown and an in-memory field centered on the Warden. Radius is 30 blocks Dormant/Imperfect, 40 Perfect, and 50 Ascendant. The field cycles Resistance II on its owner and in-range listed allies, and Weakness I on enemies in the field or tracked as captured, with pulse durations cycling 3, 2, and 1 second. The field remains active while combat is refreshed and ends after 15 seconds without qualifying combat damage. Enemies are tracked as captured until they leave a 100-block escape radius; the code does not immobilize their movement. Re-activating the shield while the owner's field is active resets that field's enemy card ledger and refreshes its combat timer. |
| **Ancient Guardian cards** | Each active field holds one shared stack per enemy entity UUID. Allied Warden attackers on the caster's side contribute to that same enemy stack. A hit uses the current stack multiplier `1.0 + cards × 0.01`, then increments the target stack by one when damage remains positive, capped at 50 cards (maximum 1.50×). Player targets display player names; other entities are marked `[MOB]`. The caster's own Warden sidebar shows the current card list, with paging when needed. This ledger is in memory and clears when the field ends or is refreshed. |
| **Unyielding** (Ascendant bonus) | While an Ascendant Ancient Guardian is active, reaching 10 health or less causes 60% damage reduction and immobilizes the Warden for the rest of the field. The Warden cannot attack, interact, move, drop/swap/use items, open inventory interactions, or break/place blocks through the guarded event paths. If the Warden dies while Unyielding is active, affected online allies are killed. |

The Ancient Guardian sidebar is visible only to the active caster's own Warden scoreboard. It does not expose another Warden's cards to allies or players of other classes. Cards and the active field do not persist across restart.

## Angel encounters

`RelicEncounterService` manages the event. It removes prior plugin-owned Angel villagers on startup, starts a wave shortly after startup, and schedules subsequent waves every `relic-villagers.interval-minutes` (60 by default). Each plan contains eight Angel slots. When exclusions are absent, the type composer seeds one of each eligible type, then randomizes the remaining slots. A type excluded after an Angel death is omitted for the next three waves; if all types are excluded, a wave is skipped while the exclusion countdown advances.

The five types are **Crazy**, **Imperfect**, **Perfect**, **Ascendant**, and **Wanderer**. They are librarian villagers with the visible orange name `Angel`, no AI, and no ordinary recipe list. Hostile-mob targeting events against plugin-owned Angels are cancelled. The type is stored as entity metadata and shown in interaction dialogue or the offer menu title.

### Spawn rules and timing

- A wave normally lasts 15 minutes. At wave end, remaining plugin-owned Angels are removed and the broadcast is `Escaped angels enchained`.
- Automatic successful wave start broadcasts `Angels escaped`; manual successful start broadcasts `Angels broke free`.
- Candidate locations are selected near configured structures, then through a bounded world-spawn fallback. Locations must be inside the actual border and the permitted inner boundary: along the candidate direction from world spawn, distance may not exceed `borderDistance / 1.5` (two-thirds of the distance from spawn to the border). The boundary calculation uses the actual border center and size. It also requires safe terrain and at least 100 horizontal blocks of separation from already selected Angels in the same world.
- Candidate attempts are bounded; failed/unsafe slots are skipped, with up to three replacement attempts per required type. A partially successful wave may contain fewer than eight Angels.
- Actual spawned entity UUIDs are recorded with the entity's actual world and X/Y/Z. Manual command output uses the successfully spawned entities' coordinates. Spawn diagnostics and rejected-candidate details are written to the server log.
- On restart, the previous wave does not resume. Existing plugin-owned Angels are removed, and a fresh startup wave is attempted.

### Interactions and trades

Left-click (attack) starts Crazy quests or the Wanderer cache quest. For Imperfect, Perfect, and Ascendant Angels it displays type-specific dialogue directing the player to trade. Right-click opens offers for trade Angels; a Crazy Angel uses right-click for submission only when the player is holding a valid quest item. Wanderers first check for their unique Heart submission and otherwise show configured custom offers.

Offers are generated once per Angel entity and remain fixed for its lifetime. Purchase clicks are server validated, including class matching, Mark balance, required equipment, inventory capacity, and transaction recovery. Repeat purchases are allowed while the offer remains available and the player can pay. Offers are not re-rolled by reopening the menu.

| Angel | Current offer behavior |
|---|---|
| Crazy | Quest-giver; no ordinary trade list. |
| Imperfect | One random-class Imperfect relic for configured tier-1 price (1,000 Marks by default). A wave-level rounded 20% selection of Imperfect Angels also receive one random-class Perfect offer. The selected offer identity and item remain fixed for that Angel. |
| Perfect | Two to four randomly selected Perfect relic offers, with class choices rolled per offer. |
| Ascendant | Offers Imperfect, Perfect, and Ascendant relics for each class; only the player's selected class is visible. Ascendant costs configured tier-3 Marks plus one Netherite Sword for Vanguard, Netherite Boots for Phantom, or Netherite Chestplate for Warden. |
| Wanderer | Up to six unique custom serialized ItemStack offers from `relic-villagers.wanderer-custom-offers`. Missing, malformed, empty, oversized, or nonpositive-price entries are skipped. Default configuration has no active custom offers. The Wanderer cache quest remains available separately. |

Tier prices use `relics.prices.level-1`, `level-2`, and `level-3`, defaulting to 1,000, 2,000, and 2,000 Marks. Shatter Swords offered through the Angel trade transaction add the existing 500-Mark acquisition cost to the configured offer price; the sword is marked prepaid when the transaction succeeds.

### Angel death consequences

Angels can be killed. For each death, the service records contributor UUIDs and applies a balance-clamped debit of up to 1,000 Marks once per contributor. The killer is marked with a temporary relic disable for up to 30 minutes. The Angel's type is excluded for the next three waves. Three tagged punishment Wardens are spawned at the stored death location and target the killer when available. If a specifically tagged punishment Warden delivers the final killing blow to a player, VenMark removes the player's genuine relic items from inventory, ender chest, and nested containers and resets applied relic tiers while retaining class selection and unrelated progression. Ordinary Wardens do not qualify. Consequence records are replayed on startup and periodically; each Warden slot is deduplicated using its death ID and slot metadata.

The plugin can retry consequences after a saved death record exists. There remains an API-level crash window between spawning a punishment Warden and persisting its UUID/slot record; startup searches the intended chunk for matching death ID and slot metadata before spawning a replacement.

## Quests and caches

### Crazy Angel quests

Crazy quests are tied to a player's UUID and quest ID, persist across Angel waves and restarts, and do not expire when the Crazy Angel despawns. A player can have one active quest across Crazy and Wanderer quests. The current random quest pool is:

1. Defeat one Warden; reward includes eight diamonds and a random 500–1,250 Marks bonus.
2. Defeat five Creepers; reward includes five diamonds and a random 500–1,250 Marks bonus.
3. Defeat eight Skeletons; reward includes four diamonds and an Angel Fragment.
4. Defeat six Pillagers; reward includes six diamonds and a random 500–1,250 Marks bonus.
5. Submit an Enchanted Book with Mending.
6. Find a Buried Treasure chest and submit the uniquely tagged Heart of the Sea obtained from that chest.

All completed quests also roll the shared item reward pool: Perfect relic (24%), Ascendant relic (6%), diamonds (13%), gold ingots (14%), enchanted golden apple (9%), totem (8%), or Angel Fragment (26%). Item submissions are quest-validated; treasure Hearts are tagged with the quest ID only after the API-reported Buried Treasure structure bounds, buried-treasure loot table, and chest contents pass validation. The chest has to contain a Heart of the Sea. Completion is saved as a claim before delivery, and an undelivered reward can be retried on join or inventory close. Reward delivery uses inventory receipts to avoid duplicate item grants.

Hunt progress and claims are persisted in `relic-encounters.yml`. Quest item objective completion and rewards are not limited to the Angel's 15-minute wave.

### Wanderer cache quests

Left-clicking a Wanderer accepts a cache quest if the player has no active quest, has selected a class, and the wave has time remaining. Only one active cache quest per player is allowed. The service searches underground around the accepting player's location using distance bands up to 400 blocks, with a bounded number of loaded search chunks and attempts. If no safe underground location is found, it tries a surface location up to 400 blocks. The player receives coordinates and whether the cache is underground or on the surface. If no valid location is found, the quest is not accepted.

Cache contents are generated and initialized in a shulker before the acceptance is reported. The contents include:

- One uniquely tagged `Wanderer's Heart` required to complete the quest.
- One Perfect relic for the accepting player's class.
- One Shatter Sword, prepaid and registered before it is exposed.
- 20–35 diamonds, 12–20 emeralds, 16–32 iron ingots, and 12–24 gold ingots.
- Two independent random standard reward rolls.
- A separate 20% chance of an additional Ascendant relic.

The random pool includes Perfect and Ascendant relics, Shatter Swords, Angel Fragments, diamonds, gold ingots, enchanted golden apples, golden apples, totems, and Ancient Debris. The standard rolls are independent and may repeat. A cache may be opened and mined by any player; the plugin does not protect it against normal looting. The owner may submit the cache's exact unique Heart by right-clicking a Wanderer while holding it in the main or offhand. A normal Heart of the Sea is not accepted. Successful submission awards 400–500 Marks inclusive and removes the matching cache block. The service identifies its own cache block by the persisted quest Heart ID.

At wave expiry, the remaining matching cache block is removed. An incomplete quest receives one 500-Mark failure penalty, which may make the balance negative. The quest remains active until wave expiry; there is no player-facing abandonment command or dialogue. Items already looted are not reclaimed. A saved in-progress Wanderer quest is expired during startup recovery rather than resumed into the new wave.

## Scoreboards and player interfaces

Each player receives a right-side sidebar titled **VENDETTA**. It always displays personal Marks and the top-five leaderboard (`--- MARKS TOP ---`, rank, name, and total). Balances are sourced from the same UUID-keyed Marks map used by the economy. Lines are truncated to the scoreboard helper's supported length, and ties are deterministic.

When the active Ancient Guardian caster is a Warden, only that caster's sidebar replaces the normal layout with the caster name, card entries, personal Marks, and leaderboard. Long card lists page automatically; the page advances on a periodic task. On end, reset, class change, death, disconnect, or world change, the active field is cleared and the regular Marks sidebar is rebuilt on the relevant update path.

Other interfaces include the initial class-selection inventory, relic ability-selection inventory, fixed Angel offer inventory, chat instructions and quest progress, and server broadcasts at Angel wave start and expiry. No client mod is required for the Marks sidebar.

## Configuration

The following are settings present in `src/main/resources/config.yml`. Reload behavior is noted where verified. `/marks reload` calls Bukkit's `reloadConfig`; the three `/vendetta settings` commands save their setting immediately. A restart is recommended after general configuration edits because several services read defaults or values during event setup and startup.

| Key | Default | Effect |
|---|---:|---|
| `rewards.boss` | `300` | Banked Marks for a mob in `rewards.boss-mobs`. Boss list takes precedence. |
| `rewards.hostile` | `10` | Reward for a mob in `rewards.hostile-mobs`. |
| `rewards.passive` | `5` | Reward for a mob in `rewards.passive-mobs`. |
| `rewards.boss-mobs` | `ENDER_DRAGON`, `WITHER`, `WARDEN`, `ELDER_GUARDIAN`, `PIGLIN_BRUTE` | Entity type names that receive the boss reward. Boss list takes precedence. |
| `rewards.hostile-mobs` | Listed below | Entity type names eligible for hostile reward. |
| `rewards.passive-mobs` | Listed below | Entity type names eligible for passive reward. |
| `pvp.base-reward` | `500` | Base banked Marks credited for an eligible, daily-limited PvP kill. |
| `pvp.bounty-percent` | `0.25` | Present in default config; no current source read was found. It does not control the observed 25% bank loss. |
| `pvp.death-penalty` | `2500` | Present in default config; no current source read was found. Current PvP transfer cap is a literal 2,500. |
| `pvp.timeout-seconds` | `15` | Present in config but not read by current source. Combat/ability timers read `combat.timeout-seconds`, which is not defined in packaged config and therefore uses the code fallback of 15 seconds. |
| `pvp.repeat-kill-cooldown-seconds` | `600` | Blocks repeated killer-victim reward settlement for this many seconds. |
| `vanguard.berserker.dormant-multiplier` | `1.10` | Dormant Berserker outgoing damage multiplier. |
| `vanguard.berserker.imperfect-multiplier` | `1.25` | Imperfect Berserker outgoing damage multiplier. |
| `vanguard.berserker.perfect-multiplier` | `1.50` | Perfect Berserker base outgoing damage multiplier. |
| `vanguard.berserker.perfect-critical-multiplier` | `1.75` | Perfect Berserker multiplier below 10 health. |
| `vanguard.bloodthirst.dormant-window-ms` | `750` | Bloodthirst Dormant trigger window. |
| `vanguard.bloodthirst.imperfect-window-ms` | `1000` | Bloodthirst Imperfect trigger window. |
| `vanguard.bloodthirst.cooldown-seconds` | `30` | Cooldown after Bloodthirst triggers. |
| `vanguard.ultimate.dormant-cooldown-seconds` | `240` | Vanguard ultimate Dormant cooldown. |
| `vanguard.ultimate.awakened-cooldown-seconds` | `480` | Vanguard ultimate Imperfect/Perfect cooldown. |
| `vanguard.ultimate.ascendant-cooldown-seconds` | `2400` | Vanguard ultimate Ascendant cooldown. |
| `vanguard.ultimate.dormant-strength-level` | `1` | Strength level while the Dormant ultimate is active. |
| `vanguard.ultimate.dormant-speed-level` | `1` | Speed level while the Dormant ultimate is active. |
| `vanguard.ultimate.awakened-strength-level` | `2` | Strength level while an upgraded non-Ascendant ultimate is active. |
| `vanguard.ultimate.awakened-speed-level` | `2` | Speed level while an upgraded ultimate is active. |
| `vanguard.ultimate.awakened-fire-resistance` | `true` | Enables Fire Resistance during an upgraded ultimate. |
| `vanguard.ultimate.ally-duration-seconds` | `6` | Perfect/Ascendant ally Strength/Speed effect duration. |
| `vanguard.ultimate.ally-range-blocks` | `16` | Distance for Vanguard ally effects at activation. |
| `vanguard.ultimate.ally-strength-level` | `2` | Ally Strength level. |
| `vanguard.ultimate.ally-speed-level` | `1` | Ally Speed level. |
| `vanguard.ultimate.ascendant-starting-strength` | `2` | Starting configured Ascendant Strength before kill scaling. |
| `vanguard.ultimate.ascendant-strength-cap` | `4` | Maximum Ascendant Strength level. |
| `vanguard.ultimate.ascendant-strength-refresh-seconds` | `60` | Configured strength refresh interval used by the helper. |
| `settings.opCooldownBypass` | `false` | Saved by `/vendetta settings opCooldownBypass`; only operators bypass Vanguard cooldown checks. |
| `settings.triggerableAngelEscapes` | `false` | Saved by `/vendetta settings triggerableAngelEscapes`; permits `/vendetta angelEscape`. |
| `settings.classChanger` | Not in packaged defaults; code fallback is `false` | Saved by `/vendetta settings classChanger`; permits class switching through `/venmark choose`. |
| `relics.exploration-drop-chance` | `0.002` | Chance per first right-click of a container location to issue a Vanguard relic. |
| `relics.exploration-perfect-chance` | `0.05` | Chance that an exploration relic is Perfect; otherwise it is Imperfect. |
| `relics.prices.level-1` | `1000` | Angel tier-1 relic offer price in Marks. |
| `relics.prices.level-2` | `2000` | Angel tier-2 relic offer price in Marks. |
| `relics.prices.level-3` | `2000` | Angel tier-3 relic offer price in Marks, in addition to class-specific Netherite equipment. |
| `bounties.max-items-per-type-per-target` | `4096` | Upper bound for `/mark bounty` item counts per target and item type. |
| `bounties.value.diamond` | `1` | Prime Target comparison value per recorded diamond. |
| `bounties.value.nether-star` | `9` | Prime Target comparison value per recorded Nether Star. |
| `relic-villagers.interval-minutes` | `60` | Interval between scheduled Angel waves after the startup wave. Minimum is one minute. |
| `relic-villagers.batch-size` | `8` | Present in config; current spawning code uses a fixed count of eight rather than reading this key. |
| `relic-villagers.lifetime-minutes` | `15` | Present in config; current wave code uses a fixed 15-minute lifetime rather than reading this key. |
| `relic-villagers.search-radius-chunks` | `96` | Chunk search radius passed to structure lookup. |
| `relic-villagers.structures.RUINED_PORTAL_DESERT.weight` | `2` | Relative configured selection weight. |
| `relic-villagers.structures.RUINED_PORTAL_JUNGLE.weight` | `1` | Relative configured selection weight. |
| `relic-villagers.structures.RUINED_PORTAL_MOUNTAIN.weight` | `1` | Relative configured selection weight. |
| `relic-villagers.structures.RUINED_PORTAL_OCEAN.weight` | `1` | Relative configured selection weight. |
| `relic-villagers.structures.PILLAGER_OUTPOST.weight` | `4` | Relative configured selection weight. |
| `relic-villagers.structures.VILLAGE_PLAINS.weight` | `5` | Relative configured selection weight. |
| `relic-villagers.structures.DESERT_PYRAMID.weight` | `3` | Relative configured selection weight. |
| `relic-villagers.structures.OCEAN_MONUMENT.weight` | `2` | Relative configured selection weight. |
| `relic-villagers.structures.END_CITY.weight` | `2` | Relative configured selection weight. |
| `relic-villagers.wanderer-custom-offers` | `{}` | Optional named offers, each with a positive `marks` value and a serialized Bukkit `ItemStack` under `item`. At most six valid offers are selected per Wanderer. |

The default hostile reward list is `ZOMBIE`, `HUSK`, `DROWNED`, `ZOMBIE_VILLAGER`, `SKELETON`, `STRAY`, `CREEPER`, `SPIDER`, `CAVE_SPIDER`, `SLIME`, `MAGMA_CUBE`, `PHANTOM`, `WITCH`, `ENDERMAN`, `SILVERFISH`, `ENDERMITE`, `VEX`, `EVOKER`, `VINDICATOR`, `PILLAGER`, `RAVAGER`, `BLAZE`, `GHAST`, `HOGLIN`, `ZOGLIN`, `PIGLIN`, `GUARDIAN`, `SHULKER`, `BREEZE`, and `BOGGED`. The default passive reward list is `COW`, `MOOSHROOM`, `PIG`, `SHEEP`, `CHICKEN`, `RABBIT`, `COD`, `SALMON`, `TROPICAL_FISH`, `PUFFERFISH`, `SQUID`, `GLOW_SQUID`, `TURTLE`, `HORSE`, `DONKEY`, `MULE`, `LLAMA`, `TRADER_LLAMA`, `CAMEL`, `SNIFFER`, `ARMADILLO`, `BEE`, `FOX`, `GOAT`, `OCELOT`, `PANDA`, `POLAR_BEAR`, `WOLF`, `CAT`, `PARROT`, `ALLAY`, `AXOLOTL`, `FROG`, and `HAPPY_GHAST`. Edit entity names using Bukkit `EntityType` names. The structure keys map to Bukkit structure registry entries. Relative weights affect candidate selection among configured entries; unavailable keys are skipped with a warning. Configuration values that have no matching code read are identified above instead of described as active controls.

To add a custom Wanderer offer, place a Bukkit-serialized item under a named entry and provide a positive Marks price. Use the actual serialized item supplied by the item-creation process; the following `PAPER` example only illustrates the expected data shape:

```yaml
relic-villagers:
  wanderer-custom-offers:
    example:
      marks: 500
      item:
        ==: org.bukkit.inventory.ItemStack
        type: PAPER
        amount: 1
```

## Persistence and lifecycle

Persistent files in the plugin data directory include:

| File | Stored state |
|---|---|
| `balances.yml` | Bank balances, transaction debit/credit receipts, and related idempotency markers. |
| `pending-marks.yml` | Bank-cap overflow credits and their transaction markers. |
| `daily-pvp.yml` | Per-UUID daily PvP reward date and usage. |
| `kill-cooldowns.yml` | Recent killer-victim repeat-kill timestamps. |
| `bounties.yml` | Mark, item, and physical-currency bounty records. |
| `allies.yml` | UUID-based ally lists. |
| `player-classes.yml` | UUID-based class selection. |
| `vanguard-data.yml` | Vanguard progression, kill progression, and cooldown timestamps. |
| `class-abilities.yml` | Phantom/Warden ability tiers and cooldown timestamps. |
| `relic-identities.yml` | Issued and consumed genuine relic identity IDs. |
| `explored-chests.yml` | Container locations already considered by relic exploration. |
| `relic-encounters.yml` | Angel offers/state, quest records and claims, paid/used Shatter Sword IDs, death-consequence journals, exclusions, and cache records. Temporary Shatter Sword relic disables are intentionally cleared at startup. |
| `combat-log.yml` | Pending combat-log death attribution. A player who logs out during recorded combat is resolved on rejoin. |
| `economy-abuse.log` | Bounded abuse/audit log records. |

YAML state writes use `SafeYaml.saveAtomically` where the service invokes it. Existing unreadable YAML files cause `SafeYaml.load` to throw and initialization to fail rather than being silently treated as empty. A zero-byte file is treated as empty with a warning. Individual services may log and ignore malformed entries according to their parser.

On shutdown, the plugin flushes balances, progression, bounties, allies, and encounter cleanup. Active Angel waves do not resume after restart. Crazy quest progress/claims persist. Wanderer quests belong to a wave and are expired during startup recovery. Ancient Guardian fields/cards, Phantom's active Void, combat windows, stuns, and Shatter Sword disable timers are transient. Shatter Sword paid and used ID sets persist; disable effects are cleared immediately after restart rather than resumed.

## Troubleshooting and verification limits

- Check the server log for the plugin's startup errors and Angel candidate warnings if a wave is empty or partial.
- If manual Angel escape is unavailable, an administrator must enable `triggerableAngelEscapes` first.
- If an Angel trade is not visible, verify the player's selected class and that the offer belongs to that class. A Wanderer with the default empty custom-offer map has no custom-item trades, though its cache quest can still be offered.
- If a cache quest is not accepted, check that the player selected a class, has no current quest, the Angel wave is active, and a safe location can be initialized.
- Do not edit persistent YAML while the server is running. Stop the server and make a backup first.
- The repository's unit tests cover helper rules and data-handling components. They do not prove command registration, event ordering, Paper entity behavior, scoreboard rendering, or reward delivery on a live server.

## Current implementation notes

These discrepancies are relevant when reading older project documents:

- `docs/VANGUARD.md` describes a `/vanguard choose` flow, cleric merchant offers, and some ability behavior that differs from current code. Class selection is through `/venmark choose`; Angel offers are generated by plugin-spawned librarian villagers; and current implementation details are recorded in this README.
- The older README described custom-trade clerics, owner-only caches, three cache reward entries, and Shatter Sword disabling all class ability triggers. Current code uses Angel librarian villagers, publicly lootable Wanderer shulker caches with a unique Heart submission, more than three cache contents, and Shatter Sword's relic-specific disable selection.
- The `/venmark ability` descriptions themselves contain known mismatches with event code: Predator's Instinct's message describes different mace behavior than its listener, Afterimage's message describes Speed III while the counter path emits particles, and the Ancient Guardian description's wording about incoming damage does not match the listener's outgoing target-stack multiplier. Prefer the implementation summaries above for these points.
- `pvp.bounty-percent`, `pvp.death-penalty`, `relic-villagers.batch-size`, and `relic-villagers.lifetime-minutes` appear in the packaged configuration but are not read by the corresponding current logic. The current implementation uses a literal 25% death transfer, a literal 2,500 transfer cap, an eight-Angel count, and a 15-minute wave lifetime.

The repository contains 16 JUnit test classes. No live Minecraft verification is represented here; run a staging Paper server to verify the interactive features before relying on their in-game behavior.
