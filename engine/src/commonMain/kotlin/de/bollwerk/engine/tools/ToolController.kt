package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason

/**
 * Hält das aktive Werkzeug, schaltet um, verteilt Zeigerereignisse und liefert die aktuelle [overlay]-Beschreibung
 * (reiner Zustandsautomat, keine Plattform-APIs; läuft auf dem Thread, der auch `GameView` liest = Sim-Thread).
 *
 * Eingabe der App (`InputController`, WP9): Welt-Koordinaten + [ToolContext] (mit `pickRadiusM` = Fang-Radius in Metern beim
 * aktuellen Zoom) + [PointerPhase]. Ausgabe: [ToolResult] (zu sendende Commands, ob die Geste übernommen wurde) und
 * [overlay] (Ghost, Snap-Ringe, Lupe, Flugbahn, Auswahl) für `renderapi.OverlayState`.
 *
 * Modi: [ToolMode.BUILD] mit einer [ToolSelection] (Material, Gerät, Reparatur, Löschen, Tür) oder [ToolMode.AIM] (Zielen).
 * Langdruck ([longPress]) öffnet in jedem Modus ein Kontextmenü (Reparatur / Löschen / Tür) für Balken bzw. Geräte; die
 * Wahl erfolgt mit [chooseContext]. "Zurück" ist [undo], der FEUER-Button [fire].
 */
class ToolController(val settings: ToolSettings = ToolSettings()) {
    val build: DefaultBuildTool = DefaultBuildTool(settings)
    val device: DefaultDeviceTool = DefaultDeviceTool()
    val aim: DefaultAimTool = DefaultAimTool(settings)
    val tap: DefaultTapTool = DefaultTapTool()
    val undoTool: DefaultUndoTool = DefaultUndoTool()

    /** Aktueller Modus. */
    var mode: ToolMode = ToolMode.NONE
        private set

    /** Aktives Werkzeug im Modus BUILD (sonst [ToolSelection.None]). */
    var selection: ToolSelection = ToolSelection.None
        private set

    /** Beschreibung der Überlagerung; nach jedem Aufruf aktuell, unveränderlich (an den Render-Thread übergebbar). */
    var overlay: ToolOverlay = ToolOverlay.NONE
        private set

    /** Offenes Kontextmenü oder `null`. */
    var contextMenu: ContextMenu? = null
        private set

    private val result = ToolResult()
    private var player = 0
    private var lastReject: RejectReason? = null
    private var rejectSeq = 0
    private var rejectTick = 0L
    private var lastAimPreview: AimPreview? = null
    private var lastAimInfo: AimInfo? = null
    private var swallow = false
    private var gestureConsumed = false
    private var picker: Picker = Picker()

    /**
     * Wechselt der lokale Spieler (Hotseat/Züge), verfallen Geste, Bau-Kette, Gerät-Ghost, Tipp-Vorschau, Waffenwahl und
     * Kontextmenü des vorigen Spielers: sonst finge der nächste an dessen Anker an (NOT_OWNER/NOT_CONNECTED).
     */
    private fun syncPlayer(ctx: ToolContext) {
        if (ctx.playerId != player) {
            player = ctx.playerId
            abortAll()
        }
    }

    /** Merkt eine Ablehnung des letzten Aufrufs für den Flash ([ToolOverlay.lastReject]). */
    private fun noteReject(ctx: ToolContext) {
        val r = result.rejected ?: return
        lastReject = r
        rejectSeq++
        rejectTick = ctx.view.tick
    }

    // ---- Werkzeugwahl ----

    /** Wählt ein Werkzeug der Toolbar (Modus BUILD); [ToolSelection.None] schaltet den Modus ab. Verwirft laufende Gesten und Ketten. */
    fun selectTool(tool: ToolSelection) {
        abortAll()
        selection = tool
        mode = if (tool == ToolSelection.None) ToolMode.NONE else ToolMode.BUILD
        when (tool) {
            is ToolSelection.Material -> build.material = tool.index
            is ToolSelection.Device -> device.deviceType = tool.index
            else -> {}
        }
        rebuildOverlay()
    }

    /** Modusschalter ZIELEN: Zielmodus (Waffe antippen, ziehen). Behält eine gewählte Waffe. */
    fun enterAimMode() {
        abortAll(keepAim = true)
        selection = ToolSelection.None
        mode = ToolMode.AIM
        rebuildOverlay()
    }

    /** Wählt die nächste ([step] = 1) bzw. vorige eigene Waffe. @return Ref oder −1. */
    fun cycleWeapon(ctx: ToolContext, step: Int = 1): Long {
        syncPlayer(ctx)
        val r = aim.cycleWeapon(ctx, step)
        rebuildOverlay()
        return r
    }

    /** Wählt Waffe [ref] (z. B. aus dem HUD). */
    fun selectWeapon(ref: Long, ctx: ToolContext): Boolean {
        syncPlayer(ctx)
        val ok = aim.select(ref, ctx)
        rebuildOverlay()
        return ok
    }

    // ---- Zeiger ----

    /** Ein Zeigerereignis in Welt-Koordinaten. Das Ergebnis wird beim nächsten Aufruf überschrieben. */
    fun pointer(phase: PointerPhase, worldX: Float, worldY: Float, ctx: ToolContext): ToolResult {
        result.clear()
        syncPlayer(ctx)
        when (phase) {
            PointerPhase.DOWN -> down(worldX, worldY, ctx)
            PointerPhase.MOVE -> move(worldX, worldY, ctx)
            PointerPhase.UP -> up(worldX, worldY, ctx)
            PointerPhase.CANCEL -> cancelGesture()
        }
        noteReject(ctx)
        rebuildOverlay()
        return result
    }

    /** Maus-Hover ohne gedrückte Taste (nur das Geräte-Werkzeug zeigt dann einen Ghost). */
    fun hover(worldX: Float, worldY: Float, ctx: ToolContext) {
        syncPlayer(ctx)
        if (mode == ToolMode.BUILD && selection is ToolSelection.Device && contextMenu == null) {
            device.hover(worldX, worldY, ctx)
            rebuildOverlay()
        }
    }

    /** Bricht die laufende Geste ab (zweiter Finger, App pausiert …); eine Bau-Kette und die Waffenwahl bleiben. */
    fun cancel() {
        result.clear()
        cancelGesture()
        rebuildOverlay()
    }

    private fun down(x: Float, y: Float, ctx: ToolContext) {
        if (contextMenu != null) {
            contextMenu = null
            swallow = true
            gestureConsumed = true
            result.consumed = true
            return
        }
        swallow = false
        gestureConsumed = false
        when (mode) {
            ToolMode.NONE -> {}
            ToolMode.AIM -> {
                aim.onDown(x, y, ctx)
                gestureConsumed = !aim.panning
            }
            ToolMode.BUILD -> when (val sel = selection) {
                is ToolSelection.Material -> {
                    build.onDown(x, y, ctx)
                    gestureConsumed = build.pressed
                }
                is ToolSelection.Device -> {
                    device.onDown(x, y, ctx)
                    gestureConsumed = true
                }
                ToolSelection.Repair, ToolSelection.Delete, ToolSelection.Door -> {
                    tap.onDown(x, y, sel, ctx)
                    gestureConsumed = tap.pressed
                }
                ToolSelection.None -> {}
            }
        }
        result.consumed = gestureConsumed
    }

    private fun move(x: Float, y: Float, ctx: ToolContext) {
        if (swallow) { result.consumed = true; return }
        if (!gestureConsumed) return
        when (mode) {
            ToolMode.NONE -> {}
            ToolMode.AIM -> {
                aim.onMove(x, y, ctx)
                val live = aim.pollLiveAim(ctx)
                if (live != null) result.commands.add(live)
            }
            ToolMode.BUILD -> when (val sel = selection) {
                is ToolSelection.Material -> build.onMove(x, y, ctx)
                is ToolSelection.Device -> device.onMove(x, y, ctx)
                ToolSelection.Repair, ToolSelection.Delete, ToolSelection.Door -> {
                    tap.onMove(x, y, sel, ctx)
                    if (tap.panning) gestureConsumed = false
                }
                ToolSelection.None -> {}
            }
        }
        result.consumed = gestureConsumed
    }

    private fun up(x: Float, y: Float, ctx: ToolContext) {
        if (swallow) {
            swallow = false
            gestureConsumed = false
            result.consumed = true
            return
        }
        if (!gestureConsumed) {
            // Gesten ohne Werkzeug (Schwenk) bzw. Tipp-Schwenk: Reste der Werkzeuge aufräumen
            tap.onUp(x, y, selection, ctx)
            return
        }
        gestureConsumed = false
        result.consumed = true
        when (mode) {
            ToolMode.NONE -> {}
            ToolMode.AIM -> {
                val cmd = aim.onUp(x, y, ctx)
                if (cmd != null) {
                    result.commands.add(cmd)
                    if (settings.releaseToFire) {
                        val fire = aim.fireCommand(ctx)
                        if (fire != null) result.commands.add(fire) else result.rejected = aim.lastReject
                    }
                } else {
                    result.rejected = aim.lastReject
                }
            }
            ToolMode.BUILD -> when (val sel = selection) {
                is ToolSelection.Material -> {
                    val cmd = build.onUp(x, y, ctx)
                    if (cmd != null) result.commands.add(cmd) else result.rejected = build.lastReject
                }
                is ToolSelection.Device -> {
                    val cmd = device.onUp(x, y, ctx)
                    if (cmd != null) result.commands.add(cmd) else result.rejected = device.lastReject
                }
                ToolSelection.Repair, ToolSelection.Delete, ToolSelection.Door -> {
                    val cmd = tap.onUp(x, y, sel, ctx)
                    if (cmd != null) result.commands.add(cmd) else result.rejected = tap.lastReject
                }
                ToolSelection.None -> {}
            }
        }
    }

    private fun cancelGesture() {
        build.cancel()
        device.cancel()
        tap.cancel()
        aim.cancel()
        swallow = false
        gestureConsumed = false
    }

    private fun abortAll(keepAim: Boolean = false) {
        build.reset()
        device.cancel()
        tap.cancel()
        if (keepAim) aim.cancel() else aim.clear()
        contextMenu = null
        swallow = false
        gestureConsumed = false
    }

    // ---- Langdruck / Kontextmenü ----

    /**
     * Langdruck bei ([worldX], [worldY]): bricht die laufende Geste ab und öffnet das Kontextmenü für das eigene Gerät bzw.
     * den eigenen Balken darunter (Balken: Reparatur, Löschen, bei Türen auch Tür; Gerät: Löschen). Die restliche Geste
     * (Move/Up) wird verschluckt. Ohne Ziel: `consumed = false`.
     */
    fun longPress(worldX: Float, worldY: Float, ctx: ToolContext): ToolResult {
        result.clear()
        syncPlayer(ctx)
        cancelGesture()
        contextMenu = null
        val view = ctx.view
        val r = ctx.pickRadiusM
        var menu: ContextMenu? = null
        val dev = picker.nearestDevice(view, ctx.playerId, worldX, worldY, r * ToolConst.DEVICE_PICK_FACTOR, false)
        if (dev >= 0) {
            val hint = tap.deviceHint(dev, ctx)
            menu = ContextMenu(true, hint.ref, worldX, worldY, listOf(ContextOption(TapAction.DELETE, hint.valid, hint.reason, hint)))
        } else {
            val b = picker.nearestBeam(view, ctx.playerId, worldX, worldY, r * ToolConst.BEAM_SNAP_FACTOR, BeamFilter.ANY, -1)
            if (b >= 0) {
                val repair = tap.beamHint(TapAction.REPAIR, b, ctx)
                val delete = tap.beamHint(TapAction.DELETE, b, ctx)
                val opts = ArrayList<ContextOption>(3)
                opts.add(ContextOption(TapAction.REPAIR, repair.valid, repair.reason, repair))
                opts.add(ContextOption(TapAction.DELETE, delete.valid, delete.reason, delete))
                if (view.tables.materials[view.beamView.material(b)].isDoor) {
                    val door = tap.beamHint(TapAction.DOOR, b, ctx)
                    opts.add(ContextOption(TapAction.DOOR, door.valid, door.reason, door))
                }
                menu = ContextMenu(false, repair.ref, worldX, worldY, opts)
            }
        }
        if (menu != null) {
            contextMenu = menu
            swallow = true
            result.consumed = true
        }
        rebuildOverlay()
        return result
    }

    /** Wählt [action] im offenen Kontextmenü: liefert das Command (erneut gegen den aktuellen Zustand geprüft) und schließt das Menü. */
    fun chooseContext(action: TapAction, ctx: ToolContext): ToolResult {
        result.clear()
        syncPlayer(ctx)
        val menu = contextMenu
        contextMenu = null
        if (menu != null) {
            var found: ContextOption? = null
            for (o in menu.options) if (o.action == action) found = o
            if (found != null) {
                result.consumed = true
                val cmd = tap.commandFor(found.hint, ctx)
                val reason = ctx.validator.validate(ctx.view, cmd)
                if (reason == null) result.commands.add(cmd) else result.rejected = reason
            }
        }
        noteReject(ctx)
        rebuildOverlay()
        return result
    }

    /** Schließt das Kontextmenü ohne Aktion. */
    fun dismissContext() {
        contextMenu = null
        rebuildOverlay()
    }

    // ---- Buttons ----

    /** FEUER-Button: `Fire` für die gewählte Waffe (oder `rejected`, z. B. `RELOADING`). */
    fun fire(ctx: ToolContext): ToolResult {
        result.clear()
        syncPlayer(ctx)
        val cmd = aim.fireCommand(ctx)
        if (cmd != null) result.commands.add(cmd) else result.rejected = aim.lastReject
        result.consumed = cmd != null
        noteReject(ctx)
        rebuildOverlay()
        return result
    }

    /** Zurück-Button: `Undo`, wenn möglich. */
    fun undo(ctx: ToolContext): ToolResult {
        result.clear()
        syncPlayer(ctx)
        val cmd = undoTool.press(ctx)
        if (cmd != null) result.commands.add(cmd) else result.rejected = undoTool.lastReject
        result.consumed = cmd != null
        noteReject(ctx)
        rebuildOverlay()
        return result
    }

    /** Zustand des Zurück-Buttons. */
    fun undoPreview(ctx: ToolContext): UndoPreview = undoTool.preview(ctx)

    /**
     * Zustand nachführen (pro Frame/Tick aufrufen): Waffenvorschau (Wind, Mündung, Waffe zerstört), laufende Ghosts
     * (Metall hat sich geändert), Kontextmenü-Ziel verschwunden. Erzeugt keine Commands.
     */
    fun refresh(ctx: ToolContext) {
        syncPlayer(ctx)
        aim.refresh(ctx)
        if (mode == ToolMode.BUILD && contextMenu == null) {
            when (selection) {
                is ToolSelection.Material -> build.refresh(ctx)
                is ToolSelection.Device -> if (device.state is DeviceToolState.Previewing) {
                    if (device.pressed) device.onMove(device.fingerX, device.fingerY, ctx) else device.hover(device.fingerX, device.fingerY, ctx)
                }
                else -> {}
            }
        }
        val menu = contextMenu
        if (menu != null) {
            val stale = if (menu.isDevice) ctx.view.deviceView.resolve(menu.targetRef) < 0 else ctx.view.beamView.resolve(menu.targetRef) < 0
            if (stale) contextMenu = null
        }
        rebuildOverlay()
    }

    // ---- Overlay ----

    private fun rebuildOverlay() {
        var ghost: GhostBeam? = null
        var ghostDevice: GhostDevice? = null
        var snaps: List<SnapMark> = emptyList()
        var loupe: LoupeRequest? = null
        var trajectory: Trajectory? = null
        var impactX = Float.NaN
        var impactY = Float.NaN
        var selDevice = -1L
        var selBeam = -1L
        var aimInfo: AimInfo? = null
        var hint: TargetHint? = null
        var rejected: RejectReason? = null

        when (mode) {
            ToolMode.NONE -> {}
            ToolMode.BUILD -> when (selection) {
                is ToolSelection.Material -> {
                    val st = build.state
                    snaps = build.snaps
                    if (st is BuildToolState.Previewing) {
                        ghost = st.ghost
                        if (!st.ghost.valid) rejected = st.ghost.reason
                    }
                    if (build.pressed) {
                        loupe = if (ghost != null) {
                            LoupeRequest(build.fingerX, build.fingerY, ghost.bx, ghost.by)
                        } else if (st is BuildToolState.FirstNodeSelected) {
                            LoupeRequest(build.fingerX, build.fingerY, st.x, st.y)
                        } else null
                    }
                }
                is ToolSelection.Device -> {
                    val st = device.state
                    if (st is DeviceToolState.Previewing) {
                        ghostDevice = st.ghost
                        if (!st.ghost.valid) rejected = st.ghost.reason
                        if (device.pressed) loupe = LoupeRequest(device.fingerX, device.fingerY, st.ghost.x, st.ghost.y)
                    }
                }
                ToolSelection.Repair, ToolSelection.Delete, ToolSelection.Door -> {
                    val h = tap.preview
                    if (h != null) {
                        hint = h
                        if (h.isDevice) selDevice = h.ref else selBeam = h.ref
                        if (!h.valid) rejected = h.reason
                    }
                }
                ToolSelection.None -> {}
            }
            ToolMode.AIM -> {
                selDevice = aim.selectedRef
                val p = aim.preview
                if (p != null && selDevice >= 0L) {
                    trajectory = p.trajectory
                    if (p.hasImpact) { impactX = p.impactX; impactY = p.impactY }
                    var info = lastAimInfo
                    if (info == null || lastAimPreview !== p) {
                        info = AimInfo(
                            deviceRef = p.deviceRef, angle = p.angle, power = p.power, elevationDeg = p.elevationDeg,
                            powerPercent = p.power * 100f, splashRadiusM = p.splashRadiusM, apexX = p.apexX, apexY = p.apexY,
                            apexHeightM = p.apexHeightM, windDriftM = p.windDriftM, hasImpact = p.hasImpact, exitedMap = p.exitedMap,
                            outcome = p.outcome, blockedReasonKey = p.blockedReasonKey,
                        )
                        lastAimPreview = p
                        lastAimInfo = info
                    }
                    aimInfo = info
                }
            }
        }
        val menu = contextMenu
        if (menu != null) {
            if (menu.isDevice) selDevice = menu.targetRef else selBeam = menu.targetRef
        }
        // nur neu bauen, wenn sich etwas geändert hat (der Aufruf pro Frame soll keinen Müll erzeugen)
        val o = overlay
        if (o.mode == mode && o.tool == selection && o.ghost == ghost && o.ghostDevice == ghostDevice && o.snaps == snaps &&
            o.loupe == loupe && o.trajectory == trajectory && o.impactX.toRawBits() == impactX.toRawBits() &&
            o.impactY.toRawBits() == impactY.toRawBits() && o.selectedDeviceRef == selDevice && o.selectedBeamRef == selBeam &&
            o.aim == aimInfo && o.hint == hint && o.contextMenu == menu && o.rejected == rejected && o.localPlayer == player &&
            o.lastReject == lastReject && o.lastRejectSeq == rejectSeq
        ) return
        overlay = ToolOverlay(
            mode = mode, tool = selection, ghost = ghost, ghostDevice = ghostDevice, snaps = snaps, loupe = loupe,
            trajectory = trajectory, impactX = impactX, impactY = impactY, selectedDeviceRef = selDevice,
            selectedBeamRef = selBeam, aim = aimInfo, hint = hint, contextMenu = menu, rejected = rejected,
            localPlayer = player, lastReject = lastReject, lastRejectSeq = rejectSeq, lastRejectTick = rejectTick,
        )
    }
}
