package de.bollwerk.app.ui.game.hud

import androidx.annotation.StringRes
import de.bollwerk.app.R
import de.bollwerk.engine.command.RejectReason

/** Anzeigename eines Bauteils über seine Content-ID (Material oder Gerät); unbekannte IDs → null. */
@StringRes
fun contentNameRes(id: String): Int? = when (id) {
    "wood" -> R.string.material_wood
    "metal" -> R.string.material_metal
    "armour" -> R.string.material_armour
    "rope" -> R.string.material_rope
    "door" -> R.string.material_door
    "reactor" -> R.string.device_reactor
    "mine" -> R.string.device_mine
    "turbine" -> R.string.device_turbine
    "workshop" -> R.string.device_workshop
    "armoury" -> R.string.device_armoury
    "upgrade_center" -> R.string.device_upgrade_center
    "factory" -> R.string.device_factory
    "mg" -> R.string.device_mg
    "sniper" -> R.string.device_sniper
    "mortar" -> R.string.device_mortar
    "cannon" -> R.string.device_cannon
    "rocket" -> R.string.device_rocket
    "laser" -> R.string.device_laser
    "repair" -> R.string.tool_repair
    "delete" -> R.string.tool_delete
    "door_tool" -> R.string.tool_door
    else -> null
}

/** Geschosstyp-Bezeichnung der Waffenkarte („Granate · Splash 2,5 m"). */
@StringRes
fun weaponKindRes(weaponId: String): Int? = when (weaponId) {
    "mg" -> R.string.kind_mg
    "sniper" -> R.string.kind_sniper
    "mortar" -> R.string.kind_mortar
    "cannon" -> R.string.kind_cannon
    "rocket" -> R.string.kind_rocket
    "laser" -> R.string.kind_laser
    else -> null
}

/**
 * Lokalisierter Text eines Ablehnungsgrunds. Die Ressourcennamen sind exakt [RejectReason.displayKey] (Vertrag der Engine);
 * ein Test prüft, dass jeder Grund abgebildet ist.
 */
@StringRes
fun rejectReasonRes(reason: RejectReason): Int = when (reason) {
    RejectReason.NOT_ENOUGH_METAL -> R.string.reject_not_enough_metal
    RejectReason.NOT_ENOUGH_ENERGY -> R.string.reject_not_enough_energy
    RejectReason.TOO_LONG -> R.string.reject_too_long
    RejectReason.TOO_SHORT -> R.string.reject_too_short
    RejectReason.NOT_CONNECTED -> R.string.reject_not_connected
    RejectReason.OUT_OF_BUILD_ZONE -> R.string.reject_out_of_build_zone
    RejectReason.BLOCKED_BY_TERRAIN -> R.string.reject_blocked_by_terrain
    RejectReason.DUPLICATE_BEAM -> R.string.reject_duplicate_beam
    RejectReason.OCCUPIED -> R.string.reject_occupied
    RejectReason.LOCKED_TECH -> R.string.reject_locked_tech
    RejectReason.INVALID_TARGET -> R.string.reject_invalid_target
    RejectReason.STALE_TARGET -> R.string.reject_stale_target
    RejectReason.NOT_OWNER -> R.string.reject_not_owner
    RejectReason.NOT_YOUR_TURN -> R.string.reject_not_your_turn
    RejectReason.RELOADING -> R.string.reject_reloading
    RejectReason.STILL_BUILDING -> R.string.reject_still_building
    RejectReason.NEEDS_ORE -> R.string.reject_needs_ore
    RejectReason.TOP_MOUNT_ONLY -> R.string.reject_top_mount_only
    RejectReason.UNIQUE_LIMIT -> R.string.reject_unique_limit
    RejectReason.REACTOR_PROTECTED -> R.string.reject_reactor_protected
    RejectReason.NOTHING_TO_UNDO -> R.string.reject_nothing_to_undo
    RejectReason.UNDO_BLOCKED -> R.string.reject_undo_blocked
    RejectReason.UNKNOWN_CONTENT -> R.string.reject_unknown_content
    RejectReason.GAME_OVER -> R.string.reject_game_over
}
