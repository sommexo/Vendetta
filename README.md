# VenMark — v1-beta build 4

VenMark is a Paper plugin for Vendetta SMP. The project targets the Paper 26.3 command API and Java 25. Paper commands are registered in code through `JavaPlugin.registerCommand`; `paper-plugin.yml` intentionally has no YAML command declarations. The Maven dependency currently resolves the Paper 26.3 pre-release API configured by `pom.xml`.

## Player commands

| Command | Description |
|---|---|
| `/venmark choose <vanguard|phantom|warden>` | Choose and lock a class. |
| `/marks` | Check banked Marks. |
| `/marks top` | View The Ledger. |
| `/deposit <amount\|hand\|offhand>` | Deposit genuine physical currency. Amounts must be multiples of 10. |
| `/withdraw <amount>` | Withdraw genuine currency; fee is 5%, rounded up to a whole Mark. |
| `/venmark deposit <amount>` and `/venmark withdraw <amount>` | Equivalent bank transactions through the `/venmark` command. |
| `/bounty <player>` | Post a tracked bounty using all genuine physical currency in your inventory. On a qualifying kill, the paid currency and 25 genuine purple dyes drop at the target's death location. |
| `/mark bounties` | View tracked bounties and Prime Target. |
| `/mark ally <player>` and `/mark unally <player>` | Manage Vanguard allies. |
| `/vanguard status` | View Vanguard progression. |
| `/venmark ability berserker` | View Berserker description and current tier. |
| `/venmark ability bloodthirst` | View Bloodthirst description and current tier. |
| `/venmark ability blood-blood-blood` | View BLOOD BLOOD BLOOD description and current tier. |
| `/venmark ability <ability>` | View the selected class ability, its description and current tier. Supports Predator's Instinct, Marked Prey, Into the Void, Afterimage, Unshaken, Sculk Pulse, Ancient Guardian, Unyielding, Berserker, Bloodthirst, BLOOD BLOOD BLOOD, and Grim Reaper. |

Physical currency is identified by VenMark PDC metadata. Purple dye is worth 10 Marks and a Nether Star is worth 1,000 Marks. Bank balances are capped at 20,000; kill rewards above the cap are stored as pending Marks. Currency items with no valid VenMark metadata have no currency value.

Right-clicking a genuine currency stack deposits as many items of that denomination as fit in the bank; unaccepted items stay in the hand. `/deposit`, `/deposit hand`, and `/deposit offhand` remain available for manual deposits.

## Administrative commands

| Command | Permission |
|---|---|
| `/venmark marks give <player> <amount>` | `venmark.marks.give` |
| `/venmark marks reset <player>` | `venmark.marks.reset` |
| `/venmark marks abuse` | `venmark.admin` |
| `/venmark currency give <player> <dye\|star> <1-64>` | `venmark.relic.give` |
| `/venmark relic give <class> <ascendant\|imperfect\|perfect> <player>` | `venmark.relic.give` or `venmark.admin` |
| `/venmark relic give <player> <class> <ability> <tier-1-3>` (legacy form) | `venmark.relic.give` or `venmark.admin` |
| `/venmark class set <player> <class>` | `venmark.class.admin` |
| `/marks give <player> <amount>`, `/marks take <player> <amount>`, `/marks check <player>`, `/marks inspect <player>`, `/marks reload` | `venmark.admin` |

LuckPerms examples:

```text
/lp group admin permission set venmark.use true
/lp group admin permission set venmark.admin true
/lp group admin permission set venmark.marks.give true
/lp group admin permission set venmark.relic.give true
/lp group admin permission set venmark.class.admin true
/lp group admin permission set venmark.marks.reset true
```

Operators with `venmark.admin` can toggle the server-wide Vanguard operator cooldown bypass with `/vendetta settings opCooldownBypass true` or `/vendetta settings opCooldownBypass false`. It defaults to `false` and is saved in `config.yml`. When enabled, only operators bypass Bloodthirst and ultimate cooldowns; active-ultimate rules still apply.

Operators with `venmark.admin` can enable class changes with `/vendetta settings classChanger true` and disable them with `/vendetta settings classChanger false`. It defaults to `false` and is saved in `config.yml`. When enabled, players can switch classes using `/venmark choose <vanguard|phantom|warden>`.

`venmark.admin` is an override for VenMark admin commands; grant narrower nodes when staff should have only specific capabilities. Balance resets are intended for confirmed offenders after an administrator reviews the abuse log. Repeated killer-victim pairs are blocked for the configured cooldown and logged; no balance reset is automatic. Daily PvP usage is stored by UUID and resets on the server's local date boundary. A player can receive at most three eligible PvP kill rewards per local day.

## PvP and bounty rules

Eligible PvP deaths require a direct player or player-shot projectile as the final damage event. The victim loses 25% of banked Marks, rounded down to whole Marks. The killer receives up to 2,500 Marks of that transfer. Any amount above that transfer cap is an explicit logged economy sink. The existing configured 500-Mark PvP reward remains and is limited by the daily 3/3 counter. Bank-cap overflow is saved as pending Marks rather than discarded.

On a mob-attributed death, 25% of carried authentic physical currency is dropped as loot, rounded down to a multiple of 10 Marks. The remaining 75% is dropped as owner-locked currency that only the victim can pick up; those return drops do not despawn or take damage while locked. If keep-inventory is enabled, only the penalty amount is removed from the player's inventory. Banked Marks are unaffected. Other inventory death drops follow the server's normal behavior.

`/bounty <player>` consumes and records the sender's genuine physical currency stacks against the target UUID. A qualifying target death drops those exact stacks unchanged plus 25 genuine purple dyes (250 Marks); normal item pickup rules apply. Bounty records are cleared and saved before drops are spawned to prevent duplicate payout after repeat death handling. The older `/mark bounty` diamond/star command remains available.

Relic give examples:

```text
/venmark relic give vanguard imperfect Steve
/venmark relic give phantom perfect Steve
/venmark relic give warden ascendant Steve
```

Relic grants use the existing relic factory and server-side metadata. The recipient must be online and have room in their inventory; a full inventory causes the command to fail without dropping or duplicating the relic. `venmark.admin` also authorizes this grant command.

## Class and relic notes

New players receive a class selection GUI. Existing Vanguard progression is migrated as a Vanguard class selection. Imperfect relics cost one genuine 1,000-Mark Nether Star and open the regular ability selection. Perfect relics cost two genuine Nether Stars and can select an eligible regular ability or ultimate, including a regular ability that is still Dormant. Ascendant upgrades an already-Perfect ultimate for two genuine Nether Stars plus the class-specific Netherite item. Designated naturally spawned clerics display a stable three-class, three-tier offer matrix; Master clerics therefore guarantee Ascendant offers for Vanguard, Warden, and Phantom. Relics use unique PDC identifiers, tier identifiers, a format version, and a persisted one-use identity ledger. Their visual glint is not used for authentication. Purchases validate progression, payment and delivery space before committing.

Combat state expires after 15 seconds without qualifying PvP damage. Direct and supported indirect player damage refreshes that timer. Combat logging is recorded across disconnects and restarts and resolved on rejoin. Warden capture state is in memory and is released on shutdown, restart, logout, world change, cancellation, or ultimate end, so it cannot persist indefinitely.

Genuine Imperfect relics open the regular ability menu. Genuine Perfect relics open the regular-and-ultimate ability menu. Ascendant relics skip that menu and apply only to the ultimate after its Perfect upgrade.

## Build

```text
mvn clean package
```

The plugin JAR is created under `target/`.

Release name: `v1-beta build 4`. The Maven version uses `v1-beta-build-4` so the coordinates and artifact filename remain valid.
