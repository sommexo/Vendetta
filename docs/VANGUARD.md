# Vanguard

## Player use

- `/vanguard choose` selects Vanguard; `/vanguard status` shows progression and ultimate cooldown.
- Right-click with a sword to activate **BLOOD BLOOD BLOOD**. The ultimate can also activate on a successful totem pop when ready.
- Start crouching with a sword in hand to open the Headweb counter window. Dormant is 1 second; either Awakened upgrade is 2 seconds.
- Berserker activates automatically while health is below half of the player's current maximum health.
- `/mark ally <player>` and `/mark unally <player>` manage the registered allies supported by a Perfect or Ascendant ultimate.

## Relics

Relics can be found when opening containers or bought from cleric villagers with Marks. Apprentice and Journeyman tiers offer the base and probabilistic Level 2 relics; a Master cleric always has one Level 3 offer. There is no relic purchase command. Right-click a relic to apply it to its matching Vanguard ability. A Level 3 relic requires a Perfect ultimate and 250 Marks by default.

## Implementation choices

- Progression and cooldown timestamps are UUID keyed in `vanguard-data.yml`.
- Headweb uses temporary Slowness as its web restraint. Moving beyond the configured escape distance, removing Slowness, or reaching its duration triggers a zero-yield TNT visual and the configured finisher damage. It never changes blocks.
- Perfect ultimate combat time resets on player-caused player damage and ends after the configured idle timeout. Ally effects use the registered list and configured range.
- Values and relic probabilities are in `config.yml` under `vanguard`, `relics`, and `bounties`.
