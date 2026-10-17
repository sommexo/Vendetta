# Vanguard

## Player use

- `/vanguard choose` selects Vanguard; `/vanguard status` shows progression and ultimate cooldown.
- Right-click with a sword to arm Bloodthirst, then crouch when hit during its timing window. The hit still deals damage; a successful counter restores the Vanguard to full health. Dormant is 0.75 seconds; Imperfect and higher inherit the 1-second window.
- A totem pop activates **BLOOD BLOOD BLOOD** when its cooldown is ready. A further totem pop while the ultimate is active ends it immediately.
- Berserker activates automatically while health is below half of the player's current maximum health.
- `/mark ally <player>` and `/mark unally <player>` manage the registered allies supported by a Perfect or Ascendant ultimate.

## Relics

Relics can be found when opening containers or bought from designated naturally spawned cleric villagers with genuine physical currency. Each relic shop displays Imperfect, Perfect, and Ascendant offers; Master clerics therefore always offer Ascendant. Imperfect costs one genuine VenMark Nether Star, Perfect costs two, and Ascendant costs two plus the class-specific Netherite item. Ascendant requires the ultimate to be Perfect. There is no relic purchase command. Right-click a relic to apply it to its matching Vanguard ability.

## Implementation choices

- Progression and cooldown timestamps are UUID keyed in `vanguard-data.yml`.
- Bloodthirst arms with a sword for 0.75 seconds while Dormant or 1 second from Imperfect onward. The triggering hit still deals damage; a successful counter restores full health. A missed window incurs a 30-second cooldown. Berserker bonuses apply to sword and axe attacks, never maces.
- All ultimate tiers last until combat ends. Dormant has a 4-minute cooldown, Perfect has an 8-minute cooldown, and Ascendant has a 40-minute cooldown. Ascendant kill-streak Strength lasts until death, including after the ultimate ends.
- Combat lasts until 15 seconds without qualifying player-versus-player damage; changing worlds ends an active ultimate. Ally effects use the registered list and configured range.
- Values and relic probabilities are in `config.yml` under `vanguard`, `relics`, and `bounties`.
