package de.bollwerk.content

/** Regeln des [ContentValidator]; jede Regel hat mindestens einen Negativtest. */
enum class ContentRule {
    /** Leere oder doppelte IDs. */
    DUPLICATE_ID,
    /** Verweis auf eine unbekannte ID (Material, Tech, Waffe, Gerät, Bauvorlage). */
    UNKNOWN_REFERENCE,
    /** Kosten, Raten oder Zeiten < 0. */
    NEGATIVE_VALUE,
    /** Nicht endliche oder unsinnige Werte (TP ≤ 0, Reichweite < Mindestreichweite, …). */
    INVALID_VALUE,
    /** Balken in einer Bauvorlage länger als [ContentValidator.MAX_BEAM_LENGTH]. */
    BEAM_TOO_LONG,
    /** Balken kürzer als [ContentValidator.MIN_BEAM_LENGTH]. */
    BEAM_TOO_SHORT,
    /** Ungültige Knotenindizes, Selbstschleifen oder doppelte Balken in einer Bauvorlage. */
    BLUEPRINT_GRAPH,
    /** Gerät verweist auf einen nicht vorhandenen Balkenindex oder `t` außerhalb 0..1. */
    DEVICE_BEAM_INDEX,
    /**
     * Gerät in einer Bauvorlage verletzt Prototyp-Platzierungsregeln (`evalDevice`): auf Seil/Tür, `t` außerhalb
     * [ContentValidator.MIN_DEVICE_T]..[ContentValidator.MAX_DEVICE_T], Turbine nicht nach oben, Mindestabstand
     * zu einem anderen Gerät derselben Vorlage unterschritten.
     */
    DEVICE_PLACEMENT,
    /** Startfestung/Bauplan hat nicht genau einen Reaktor oder ein Spieler nicht genau eine Startfestung. */
    REACTOR_COUNT,
    /** Tech schaltet etwas frei, das diese Tech nicht verlangt (oder umgekehrt). */
    UNLOCK_MISMATCH,
    /** Tech wird von keinem Gebäude gewährt. */
    UNGRANTED_TECH,
    /** Zyklus in den Tech-Voraussetzungen. */
    TECH_CYCLE,
    /** Waffengerät ohne Waffe, Nicht-Waffe mit Waffe, oder Waffe ohne/mit mehreren Geräten. */
    WEAPON_LINK,
    /** Gelände, Plateaus, Bauzonen, Erz, Fundamente oder Wind inkonsistent. */
    MAP_GEOMETRY,
    /** Startfestung liegt nicht auf dem Boden/in der Bauzone ihres Spielers. */
    MAP_FORT,
    /** Mine einer Startfestung steht nicht über eigenem Erz. */
    MINE_ORE,
    /**
     * KI-Plan: Schwierigkeits-Tags, Schritte, Reihenfolge oder Tech-Voraussetzungen fehlerhaft, oder der Plan
     * passt nicht auf die Startfestung(en) der Karten, auf die er aufsetzt (Materialkonflikt, Gerät zu nah an
     * einem Gerät der Startfestung, verankerter Knoten ohne Fundament, bereits vorhandene Teile oder
     * eindeutige Geräte in den Schritten).
     */
    AI_PLAN,
    /** Zeit (Nachladen, Salvenabstand, Strahldauer, Bauzeit) liegt nicht nahe an einer ganzen Tickzahl (1/60 s). */
    TICK_ALIGNMENT,
}

/** Gefundenes Problem: Regel, Ort (z. B. `weapon 'mortar'`) und Klartext. */
data class ContentIssue(val rule: ContentRule, val where: String, val message: String) {
    override fun toString(): String = "[$rule] $where: $message"
}

/**
 * Prüft Content-Daten auf Konsistenz. Arbeitet auf dem rohen [ContentPack] (nicht auf [ContentDb]), damit
 * auch kaputte Daten beschrieben werden können, statt beim Laden nur die erste Ausnahme zu werfen.
 * Der Validator ist rein und deterministisch; er ändert nichts.
 */
object ContentValidator {
    /** Maximale Balkenlänge in m (Stil-Bibel §1, `SimConfig.maxBeamLength`). */
    const val MAX_BEAM_LENGTH: Float = 6f

    /** Minimale Balkenlänge in m (`SimConfig.minBeamLength`). */
    const val MIN_BEAM_LENGTH: Float = 0.5f

    /** Kleinster/größter Balkenparameter `t` für Geräte (Prototyp `clamp(t, .15, .85)`). */
    const val MIN_DEVICE_T: Float = 0.15f
    const val MAX_DEVICE_T: Float = 0.85f

    /** Toleranz für Längen-/Höhenvergleiche in m. */
    private const val EPS = 1e-3f

    /** Knoten gelten als identisch, wenn sie näher als `SimConfig.nodeMergeRadius` liegen (m). */
    private const val NODE_MATCH = 0.05f

    /** Sim-Tickrate (`SimConfig.dt = 1/60`); Zeiten sollen nahe an ganzen Ticks liegen. */
    private const val TICKS_PER_SECOND = 60f

    /** Größte erlaubte Abweichung einer Zeit von der nächsten ganzen Tickzahl (in Ticks); 0,5 wäre ein Rundungs-Gleichstand. */
    private const val TICK_TOLERANCE = 0.25f

    /** Erdbeschleunigung der Sim (`SimConfig.gravity`) für die Ballistik-Plausibilitätsprüfungen. */
    private const val GRAVITY = 9.81f

    /** Höhe einer Festung über dem Boden in m (Abschussort über Bodenhöhe) für die Flugzeit-Prüfung. */
    private const val FORT_HEIGHT = 10f

    /** Zulässige Phasen eines KI-Plans in Bau-Reihenfolge. */
    val PHASES: List<String> = listOf("economy", "workshop", "walls", "armour", "weapons", "factory", "reactor_cover")

    /** Schwierigkeits-Tags der KI-Pläne (`ai` + genau eines davon). */
    val DIFFICULTIES: List<String> = listOf("easy", "normal", "hard")

    fun validate(db: ContentDb): List<ContentIssue> = validate(db.toPack())

    /** Wirft [ContentException] mit allen Problemen, wenn [validate] etwas findet. */
    fun requireValid(db: ContentDb) {
        val issues = validate(db)
        if (issues.isNotEmpty()) throw ContentException("content invalid:\n" + issues.joinToString("\n"))
    }

    fun validate(pack: ContentPack): List<ContentIssue> {
        val c = Collector()
        val ctx = Ctx(pack)
        checkIds(pack, c)
        checkMaterials(pack, ctx, c)
        checkWeapons(pack, c)
        checkDevices(pack, ctx, c)
        checkTechs(pack, ctx, c)
        for (bp in pack.blueprints) checkBlueprint(bp, ctx, c)
        for (m in pack.maps) checkMap(m, ctx, c)
        checkAiPlans(pack, ctx, c)
        return c.issues
    }

    // ---------------------------------------------------------------------------------------------

    private class Collector {
        val issues = ArrayList<ContentIssue>()
        fun add(rule: ContentRule, where: String, msg: String) { issues += ContentIssue(rule, where, msg) }
    }

    private class Ctx(val pack: ContentPack) {
        val materials = pack.materials.associateBy { it.id }
        val devices = pack.devices.associateBy { it.id }
        val weapons = pack.weapons.associateBy { it.id }
        val techs = pack.techs.associateBy { it.id }
        val blueprints = pack.blueprints.associateBy { it.id }
        /** Tech → Gebäude, die sie gewähren. */
        val granters: Map<String, List<DeviceDef>> = pack.devices.filter { it.grantsTech != null }.groupBy { it.grantsTech!! }
    }

    private fun finite(v: Float) = !v.isNaN() && !v.isInfinite()

    private fun checkIds(p: ContentPack, c: Collector) {
        fun <T> dup(kind: String, list: List<T>, id: (T) -> String) {
            val seen = HashSet<String>()
            for (x in list) {
                val k = id(x)
                if (k.isBlank()) c.add(ContentRule.DUPLICATE_ID, kind, "blank id")
                else if (!seen.add(k)) c.add(ContentRule.DUPLICATE_ID, "$kind '$k'", "duplicate id")
            }
        }
        dup("material", p.materials) { it.id }; dup("device", p.devices) { it.id }; dup("weapon", p.weapons) { it.id }
        dup("tech", p.techs) { it.id }; dup("map", p.maps) { it.id }; dup("blueprint", p.blueprints) { it.id }
    }

    /** Prüft [v]: endlich (INVALID_VALUE) und ≥ 0 (NEGATIVE_VALUE). */
    private fun nonNeg(c: Collector, where: String, name: String, v: Float) {
        if (!finite(v)) c.add(ContentRule.INVALID_VALUE, where, "$name is not finite")
        else if (v < 0f) c.add(ContentRule.NEGATIVE_VALUE, where, "$name = $v < 0")
    }

    private fun positive(c: Collector, where: String, name: String, v: Float) {
        if (!finite(v) || v <= 0f) c.add(ContentRule.INVALID_VALUE, where, "$name = $v must be > 0")
    }

    private fun checkMaterials(p: ContentPack, ctx: Ctx, c: Collector) {
        for (m in p.materials) {
            val w = "material '${m.id}'"
            nonNeg(c, w, "costPerM", m.costPerM)
            positive(c, w, "hp", m.hp); positive(c, w, "density", m.density); positive(c, w, "stiffness", m.stiffness)
            positive(c, w, "tensionLimit", m.tensionLimit); positive(c, w, "thickness", m.thickness)
            nonNeg(c, w, "damageFactor", m.damageFactor); nonNeg(c, w, "damping", m.damping)
            nonNeg(c, w, "compressionLimit", m.compressionLimit)
            if (!m.tensionOnly && m.compressionLimit <= 0f) c.add(ContentRule.INVALID_VALUE, w, "compressionLimit must be > 0 unless tensionOnly")
            positive(c, w, "restLengthFactor", m.restLengthFactor)
            m.requiresTech?.let { if (it !in ctx.techs) c.add(ContentRule.UNKNOWN_REFERENCE, w, "requiresTech '$it' unknown") }
        }
    }


    /** Meldet [TICK_ALIGNMENT], wenn [seconds] nicht nahe an einer ganzen Tickzahl liegt (Rundung wäre uneindeutig). */
    private fun tickAligned(c: Collector, where: String, name: String, seconds: Float) {
        if (!finite(seconds) || seconds <= 0f) return
        val ticks = seconds * TICKS_PER_SECOND
        val off = kotlin.math.abs(ticks - kotlin.math.round(ticks))
        if (off > TICK_TOLERANCE) c.add(ContentRule.TICK_ALIGNMENT, where, "$name = $seconds s is ${ticks} ticks, not within $TICK_TOLERANCE of a whole tick")
    }

    /**
     * Tiefster Fall unter den Abschussort in m, mit dem Geschosse rechnen müssen: größter Höhenunterschied
     * zwischen Gelände und Bodenhöhe der Karten plus [FORT_HEIGHT].
     */
    private fun fallAllowance(p: ContentPack): Float {
        var drop = 0f
        for (m in p.maps) {
            val low = m.terrain.filter { finite(it.y) && it.x >= 0f && it.x <= m.width }.maxOfOrNull { it.y } ?: continue
            val base = m.baseY.filter { finite(it) }.minOrNull() ?: continue
            drop = maxOf(drop, low - base)
        }
        return drop + FORT_HEIGHT
    }

    /**
     * Ballistik-Plausibilität: die Lebensdauer muss für den steilsten Schuss bei voller Kraft inklusive Fall
     * um [drop] m reichen (sonst verschwinden Geschosse mitten im Flug), und `maxRange` darf die physikalische
     * Reichweite `v²/(g·gravityScale)` nicht übersteigen.
     */
    private fun checkBallistics(x: WeaponDef, drop: Float, w: String, c: Collector) {
        if (!finite(x.muzzleSpeed) || !finite(x.gravityScale) || x.gravityScale <= 0f || x.muzzleSpeed <= 0f) return
        val g = GRAVITY * x.gravityScale
        if (finite(x.maxAimDeg) && x.maxAimDeg in -90f..90f && finite(x.lifetimeSeconds) && x.lifetimeSeconds > 0f) {
            val vy = x.muzzleSpeed * kotlin.math.sin(x.maxAimDeg * (kotlin.math.PI.toFloat() / 180f))
            val flight = (vy + kotlin.math.sqrt(vy * vy + 2f * g * drop)) / g
            if (x.lifetimeSeconds < flight) {
                c.add(ContentRule.INVALID_VALUE, w, "lifetimeSeconds ${x.lifetimeSeconds} < flight time $flight s at ${x.maxAimDeg} deg, full power, falling $drop m")
            }
        }
        val physRange = x.muzzleSpeed * x.muzzleSpeed / g
        if (finite(x.maxRange) && x.maxRange > physRange * 1.001f) {
            c.add(ContentRule.INVALID_VALUE, w, "maxRange ${x.maxRange} exceeds physical range $physRange m (v^2/(g*gravityScale))")
        }
    }

    private fun checkWeapons(p: ContentPack, c: Collector) {
        val drop = fallAllowance(p)
        for (x in p.weapons) {
            val w = "weapon '${x.id}'"
            nonNeg(c, w, "damage", x.damage); nonNeg(c, w, "splashRadius", x.splashRadius); nonNeg(c, w, "splashDamage", x.splashDamage)
            nonNeg(c, w, "minRange", x.minRange); nonNeg(c, w, "shotMetal", x.shotMetal); nonNeg(c, w, "shotEnergy", x.shotEnergy)
            nonNeg(c, w, "burstIntervalSeconds", x.burstIntervalSeconds); nonNeg(c, w, "spreadDeg", x.spreadDeg)
            nonNeg(c, w, "directImpulse", x.directImpulse); nonNeg(c, w, "explosionImpulse", x.explosionImpulse)
            nonNeg(c, w, "recoilImpulse", x.recoilImpulse); nonNeg(c, w, "igniteRadius", x.igniteRadius)
            nonNeg(c, w, "deviceDamageFactor", x.deviceDamageFactor); nonNeg(c, w, "gravityScale", x.gravityScale)
            nonNeg(c, w, "lifetimeSeconds", x.lifetimeSeconds); nonNeg(c, w, "beamSeconds", x.beamSeconds); nonNeg(c, w, "muzzleSpeed", x.muzzleSpeed)
            if (x.piercesBeams < 0) c.add(ContentRule.NEGATIVE_VALUE, w, "piercesBeams < 0")
            positive(c, w, "reloadSeconds", x.reloadSeconds); positive(c, w, "maxRange", x.maxRange); positive(c, w, "projectileRadius", x.projectileRadius)
            if (finite(x.minRange) && finite(x.maxRange) && x.minRange > x.maxRange) c.add(ContentRule.INVALID_VALUE, w, "minRange > maxRange")
            if (x.shotsPerBurst < 1) c.add(ContentRule.INVALID_VALUE, w, "shotsPerBurst < 1")
            if (x.shotsPerBurst > 1 && x.burstIntervalSeconds <= 0f) c.add(ContentRule.INVALID_VALUE, w, "burst needs burstIntervalSeconds > 0")
            if (!finite(x.minAimDeg) || !finite(x.maxAimDeg) || x.minAimDeg > x.maxAimDeg || x.minAimDeg < -90f || x.maxAimDeg > 90f) {
                c.add(ContentRule.INVALID_VALUE, w, "aim range ${x.minAimDeg}..${x.maxAimDeg} must be within -90..90 and ordered")
            } else if (x.defaultAimDeg < x.minAimDeg || x.defaultAimDeg > x.maxAimDeg) {
                c.add(ContentRule.INVALID_VALUE, w, "defaultAimDeg ${x.defaultAimDeg} outside aim range")
            }
            if (!finite(x.defaultPower) || x.defaultPower <= 0f || x.defaultPower > 1f) c.add(ContentRule.INVALID_VALUE, w, "defaultPower ${x.defaultPower} not in (0,1]")
            when (x.mode) {
                WeaponModeDef.BALLISTIC -> {
                    if (x.muzzleSpeed <= 0f) c.add(ContentRule.INVALID_VALUE, w, "ballistic weapon needs muzzleSpeed > 0")
                    if (x.lifetimeSeconds <= 0f) c.add(ContentRule.INVALID_VALUE, w, "ballistic weapon needs lifetimeSeconds > 0")
                    checkBallistics(x, drop, w, c)
                }
                WeaponModeDef.BEAM -> if (x.beamSeconds <= 0f) c.add(ContentRule.INVALID_VALUE, w, "beam weapon needs beamSeconds > 0")
                WeaponModeDef.HITSCAN -> {}
            }
            tickAligned(c, w, "reloadSeconds", x.reloadSeconds)
            if (x.shotsPerBurst > 1) tickAligned(c, w, "burstIntervalSeconds", x.burstIntervalSeconds)
            if (x.mode == WeaponModeDef.BEAM) tickAligned(c, w, "beamSeconds", x.beamSeconds)
        }
    }

    private fun checkDevices(p: ContentPack, ctx: Ctx, c: Collector) {
        val users = HashMap<String, Int>()
        for (d in p.devices) {
            val w = "device '${d.id}'"
            nonNeg(c, w, "costMetal", d.costMetal); nonNeg(c, w, "costEnergy", d.costEnergy); nonNeg(c, w, "buildSeconds", d.buildSeconds)
            nonNeg(c, w, "metalPerSec", d.metalPerSec); nonNeg(c, w, "energyPerSec", d.energyPerSec); nonNeg(c, w, "pivotOffset", d.pivotOffset)
            nonNeg(c, w, "barrelLength", d.barrelLength); nonNeg(c, w, "minSpacing", d.minSpacing); nonNeg(c, w, "oreRadius", d.oreRadius)
            positive(c, w, "hp", d.hp); positive(c, w, "mass", d.mass); positive(c, w, "hitRadius", d.hitRadius)
            if (!finite(d.mountOffset)) c.add(ContentRule.INVALID_VALUE, w, "mountOffset is not finite")
            tickAligned(c, w, "buildSeconds", d.buildSeconds)
            d.requiresTech?.let { if (it !in ctx.techs) c.add(ContentRule.UNKNOWN_REFERENCE, w, "requiresTech '$it' unknown") }
            d.grantsTech?.let { if (it !in ctx.techs) c.add(ContentRule.UNKNOWN_REFERENCE, w, "grantsTech '$it' unknown") }
            val wid = d.weapon
            if (wid != null && wid !in ctx.weapons) c.add(ContentRule.UNKNOWN_REFERENCE, w, "weapon '$wid' unknown")
            if (d.category == DeviceCategory.WEAPON && wid == null) c.add(ContentRule.WEAPON_LINK, w, "weapon device has no weapon")
            if (d.category != DeviceCategory.WEAPON && wid != null) c.add(ContentRule.WEAPON_LINK, w, "non-weapon device references weapon '$wid'")
            if (wid != null) users.merge(wid, 1, Int::plus)
        }
        for (x in p.weapons) {
            val n = users[x.id] ?: 0
            if (n != 1) c.add(ContentRule.WEAPON_LINK, "weapon '${x.id}'", "must belong to exactly one device, found $n")
        }
    }

    private fun checkTechs(p: ContentPack, ctx: Ctx, c: Collector) {
        for (t in p.techs) {
            val w = "tech '${t.id}'"
            for (r in t.requires) if (r !in ctx.techs) c.add(ContentRule.UNKNOWN_REFERENCE, w, "requires unknown tech '$r'")
            for (u in t.unlocks) {
                val isMat = u in ctx.materials; val isDev = u in ctx.devices; val isWpn = u in ctx.weapons
                if (!isMat && !isDev && !isWpn) { c.add(ContentRule.UNKNOWN_REFERENCE, w, "unlocks unknown id '$u'"); continue }
                // Was dieses Ding verlangt: Material, Gerät oder Waffe (über das Waffengerät)
                if (isMat && ctx.materials.getValue(u).requiresTech != t.id) c.add(ContentRule.UNLOCK_MISMATCH, w, "unlocks material '$u' which requires '${ctx.materials.getValue(u).requiresTech}'")
                if (isDev && ctx.devices.getValue(u).requiresTech != t.id) c.add(ContentRule.UNLOCK_MISMATCH, w, "unlocks device '$u' which requires '${ctx.devices.getValue(u).requiresTech}'")
                if (!isDev && isWpn) {
                    for (d in p.devices.filter { it.weapon == u }) if (d.requiresTech != t.id) c.add(ContentRule.UNLOCK_MISMATCH, w, "unlocks weapon '$u' whose device '${d.id}' requires '${d.requiresTech}'")
                }
            }
            if (ctx.granters[t.id].isNullOrEmpty()) c.add(ContentRule.UNGRANTED_TECH, w, "no device grants this tech")
        }
        // Umgekehrt: Wer eine Tech verlangt, muss von ihr freigeschaltet werden (Techgebäude: über `requires` der eigenen Tech)
        for (m in p.materials) m.requiresTech?.let { r -> ctx.techs[r]?.let { t -> if (m.id !in t.unlocks) c.add(ContentRule.UNLOCK_MISMATCH, "material '${m.id}'", "requires '$r' which does not list it in unlocks") } }
        for (d in p.devices) d.requiresTech?.let { r ->
            val t = ctx.techs[r] ?: return@let
            val own = d.grantsTech?.let { ctx.techs[it] }
            val listed = d.id in t.unlocks || (d.weapon != null && d.weapon in t.unlocks)
            val prerequisite = own != null && r in own.requires
            if (!listed && !prerequisite) c.add(ContentRule.UNLOCK_MISMATCH, "device '${d.id}'", "requires '$r' which neither unlocks it nor is a prerequisite of the tech it grants")
        }
        // Zyklen (DFS, Zustände 0/1/2)
        val state = HashMap<String, Int>()
        fun visit(id: String): Boolean {
            when (state[id]) { 1 -> return true; 2 -> return false }
            state[id] = 1
            for (r in ctx.techs[id]?.requires.orEmpty()) if (r in ctx.techs && visit(r)) return true
            state[id] = 2
            return false
        }
        for (t in p.techs) if (visit(t.id)) { c.add(ContentRule.TECH_CYCLE, "tech '${t.id}'", "cyclic requires"); break }
    }

    // ---------------------------------------------------------------------------------------------

    private fun len(a: BpNode, b: BpNode): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun checkBlueprint(bp: BlueprintDef, ctx: Ctx, c: Collector) {
        val w = "blueprint '${bp.id}'"
        for ((i, n) in bp.nodes.withIndex()) if (!finite(n.x) || !finite(n.y)) c.add(ContentRule.INVALID_VALUE, w, "node $i not finite")
        val pairs = HashSet<Long>()
        for ((i, b) in bp.beams.withIndex()) {
            if (b.a !in bp.nodes.indices || b.b !in bp.nodes.indices || b.a == b.b) {
                c.add(ContentRule.BLUEPRINT_GRAPH, w, "beam $i has invalid nodes ${b.a}-${b.b}"); continue
            }
            val lo = minOf(b.a, b.b).toLong(); val hi = maxOf(b.a, b.b).toLong()
            if (!pairs.add(lo * 100000L + hi)) c.add(ContentRule.BLUEPRINT_GRAPH, w, "beam $i duplicates nodes ${b.a}-${b.b}")
            if (b.material !in ctx.materials) c.add(ContentRule.UNKNOWN_REFERENCE, w, "beam $i uses unknown material '${b.material}'")
            val l = len(bp.nodes[b.a], bp.nodes[b.b])
            if (l > MAX_BEAM_LENGTH + EPS) c.add(ContentRule.BEAM_TOO_LONG, w, "beam $i is $l m > $MAX_BEAM_LENGTH m")
            if (l < MIN_BEAM_LENGTH - EPS) c.add(ContentRule.BEAM_TOO_SHORT, w, "beam $i is $l m < $MIN_BEAM_LENGTH m")
        }
        for ((i, d) in bp.devices.withIndex()) {
            if (d.beam !in bp.beams.indices) c.add(ContentRule.DEVICE_BEAM_INDEX, w, "device $i ('${d.type}') on missing beam ${d.beam}")
            if (!finite(d.t) || d.t < 0f || d.t > 1f) c.add(ContentRule.DEVICE_BEAM_INDEX, w, "device $i ('${d.type}') t=${d.t} not in 0..1")
            if (d.type !in ctx.devices) c.add(ContentRule.UNKNOWN_REFERENCE, w, "device $i uses unknown type '${d.type}'")
        }
        checkPlacement(bp, ctx, c)
        if ("start" in bp.tags || "ai" in bp.tags) {
            val n = bp.devices.count { ctx.devices[it.type]?.category == DeviceCategory.REACTOR }
            if (n != 1) c.add(ContentRule.REACTOR_COUNT, w, "needs exactly one reactor, found $n")
        }
    }


    /**
     * Mount point of a blueprint device (prototype `devicePos`): `lerp(A, B, t) + n · thickness/2` with
     * `n = (-dy, dx)/L`, flipped for `sideNegative`. Returns `[x, y, nx, ny]`, or null if the references are broken.
     */
    private fun mountPoint(bp: BlueprintDef, d: BpDevice, ctx: Ctx): FloatArray? {
        val b = bp.beams.getOrNull(d.beam) ?: return null
        val a = bp.nodes.getOrNull(b.a) ?: return null
        val e = bp.nodes.getOrNull(b.b) ?: return null
        val mat = ctx.materials[b.material] ?: return null
        val dx = e.x - a.x; val dy = e.y - a.y
        val l = kotlin.math.sqrt(dx * dx + dy * dy)
        if (!finite(l) || l <= 0f || !finite(d.t)) return null
        var nx = -dy / l; var ny = dx / l
        if (d.sideNegative) { nx = -nx; ny = -ny }
        val h = mat.thickness / 2f
        return floatArrayOf(a.x + dx * d.t + nx * h, a.y + dy * d.t + ny * h, nx, ny)
    }

    private fun spacingOf(a: DeviceDef?, b: DeviceDef?): Float = maxOf(a?.minSpacing ?: 0f, b?.minSpacing ?: 0f)

    /** Prototyp-Platzierungsregeln für Geräte einer Vorlage ([ContentRule.DEVICE_PLACEMENT]). */
    private fun checkPlacement(bp: BlueprintDef, ctx: Ctx, c: Collector) {
        val w = "blueprint '${bp.id}'"
        val mounts = ArrayList<FloatArray?>(bp.devices.size)
        for ((i, d) in bp.devices.withIndex()) {
            val dev = ctx.devices[d.type]
            val beam = bp.beams.getOrNull(d.beam)
            val mat = beam?.let { ctx.materials[it.material] }
            if (mat != null && (mat.tensionOnly || mat.isDoor)) c.add(ContentRule.DEVICE_PLACEMENT, w, "device $i ('${d.type}') sits on ${if (mat.isDoor) "door" else "rope"} beam ${d.beam}")
            if (finite(d.t) && d.t in 0f..1f && (d.t < MIN_DEVICE_T || d.t > MAX_DEVICE_T)) {
                c.add(ContentRule.DEVICE_PLACEMENT, w, "device $i ('${d.type}') t=${d.t} outside $MIN_DEVICE_T..$MAX_DEVICE_T")
            }
            val m = mountPoint(bp, d, ctx)
            mounts += m
            if (m != null && dev?.mountRule == MountRuleDef.TOP_ONLY && m[3] > -0.5f) {
                c.add(ContentRule.DEVICE_PLACEMENT, w, "device $i ('${d.type}') must face up (normal y ${m[3]} > -0.5)")
            }
        }
        for (i in mounts.indices) for (j in i + 1 until mounts.size) {
            val a = mounts[i] ?: continue; val b = mounts[j] ?: continue
            val dist = kotlin.math.hypot(a[0] - b[0], a[1] - b[1])
            val min = spacingOf(ctx.devices[bp.devices[i].type], ctx.devices[bp.devices[j].type])
            if (dist < min) c.add(ContentRule.DEVICE_PLACEMENT, w, "devices $i ('${bp.devices[i].type}') and $j ('${bp.devices[j].type}') are $dist m apart, minimum $min m")
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun terrainY(t: List<PointDef>, x: Float): Float {
        if (x <= t.first().x) return t.first().y
        for (i in 1 until t.size) if (x <= t[i].x) {
            val a = t[i - 1]; val b = t[i]
            val f = (x - a.x) / (b.x - a.x)
            return a.y + (b.y - a.y) * f
        }
        return t.last().y
    }

    private fun checkMap(m: MapDef, ctx: Ctx, c: Collector) {
        val w = "map '${m.id}'"
        val geo = { msg: String -> c.add(ContentRule.MAP_GEOMETRY, w, msg) }
        if (m.playerCount < 1) { geo("playerCount < 1"); return }
        if (!finite(m.width) || m.width <= 0f || !finite(m.height) || m.height <= 0f) geo("width/height must be > 0")
        var terrainOk = m.terrain.size >= 2
        if (!terrainOk) geo("terrain needs >= 2 points")
        for (i in 1 until m.terrain.size) if (m.terrain[i].x <= m.terrain[i - 1].x) { geo("terrain x not strictly ascending at $i"); terrainOk = false }
        if (terrainOk && (m.terrain.first().x > 0f || m.terrain.last().x < m.width)) geo("terrain must cover 0..${m.width}")
        if (m.windMin > m.windMax) geo("windMin > windMax")
        if (m.baseY.isNotEmpty() && m.baseY.size != m.playerCount) geo("baseY needs ${m.playerCount} entries")
        val ownerOk = { o: Int -> o in 0 until m.playerCount }
        for (z in m.buildZones) {
            if (!ownerOk(z.owner)) geo("build zone owner ${z.owner} out of range")
            if (z.x0 >= z.x1 || z.x0 < 0f || z.x1 > m.width) geo("build zone ${z.owner} [${z.x0}, ${z.x1}] invalid")
        }
        for (o in 0 until m.playerCount) if (m.buildZones.count { it.owner == o } != 1) geo("player $o needs exactly one build zone")
        fun zoneOf(o: Int) = m.buildZones.firstOrNull { it.owner == o }
        if (terrainOk) {
            for (pl in m.plateaus) {
                for (x in listOf(pl.x0, (pl.x0 + pl.x1) / 2f, pl.x1)) if (kotlin.math.abs(terrainY(m.terrain, x) - pl.y) > 0.05f) {
                    geo("plateau [${pl.x0}, ${pl.x1}] y=${pl.y} does not match terrain at x=$x"); break
                }
            }
            for (f in m.foundations) {
                if (!ownerOk(f.owner)) { geo("foundation owner ${f.owner} out of range"); continue }
                if (kotlin.math.abs(terrainY(m.terrain, f.x) - f.y) > 0.1f) geo("foundation at x=${f.x} y=${f.y} not on terrain")
                val z = zoneOf(f.owner)
                if (z != null && (f.x < z.x0 || f.x > z.x1)) geo("foundation at x=${f.x} outside build zone of player ${f.owner}")
            }
        }
        for (o in m.ores) {
            if (!ownerOk(o.owner)) { geo("ore owner ${o.owner} out of range"); continue }
            val z = zoneOf(o.owner)
            if (z != null && (o.x < z.x0 || o.x > z.x1)) geo("ore at x=${o.x} outside build zone of player ${o.owner}")
        }
        // Startfestungen: genau eine je Spieler, Reaktor, Lage
        for (o in 0 until m.playerCount) {
            val forts = m.startForts.filter { it.owner == o }
            if (forts.size != 1) { c.add(ContentRule.REACTOR_COUNT, w, "player $o needs exactly one start fort, found ${forts.size}"); continue }
            val f = forts[0]
            val bp = ctx.blueprints[f.blueprint]
            if (bp == null) { c.add(ContentRule.UNKNOWN_REFERENCE, w, "start fort blueprint '${f.blueprint}' unknown"); continue }
            val reactors = bp.devices.count { ctx.devices[it.type]?.category == DeviceCategory.REACTOR }
            if (reactors != 1) c.add(ContentRule.REACTOR_COUNT, w, "start fort of player $o has $reactors reactors, needs exactly 1")
            if (!terrainOk) continue
            val baseY = m.baseY.getOrNull(o) ?: continue
            val z = zoneOf(o)
            fun worldX(x: Float) = if (f.mirror) f.originX - x else f.originX + x
            for ((i, n) in bp.nodes.withIndex()) {
                val wx = worldX(n.x)
                if (z != null && (wx < z.x0 - EPS || wx > z.x1 + EPS)) c.add(ContentRule.MAP_FORT, w, "start fort node $i at x=$wx outside build zone of player $o")
                if (n.anchored && kotlin.math.abs(baseY + n.y - terrainY(m.terrain, wx)) > 0.1f) c.add(ContentRule.MAP_FORT, w, "anchored start fort node $i at x=$wx is not on the ground")
            }
            val ores = m.ores.filter { it.owner == o }
            for ((i, d) in bp.devices.withIndex()) {
                val dev = ctx.devices[d.type] ?: continue
                if (!dev.requiresOre || d.beam !in bp.beams.indices) continue
                val b = bp.beams[d.beam]
                if (b.a !in bp.nodes.indices || b.b !in bp.nodes.indices) continue
                val x = worldX(bp.nodes[b.a].x + (bp.nodes[b.b].x - bp.nodes[b.a].x) * d.t)
                if (ores.none { kotlin.math.abs(it.x - x) <= dev.oreRadius }) c.add(ContentRule.MINE_ORE, w, "device $i ('${d.type}') of player $o at x=$x has no ore within ${dev.oreRadius} m")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Wo ein KI-Plan auf der Startfestung [base] aufsetzt: Zuordnung seiner Teile zu Teilen von [base] (Index oder −1). */
    private class Overlay(val nodes: IntArray, val beams: IntArray, val devices: IntArray)

    /**
     * Ordnet Knoten über die Position (innerhalb [NODE_MATCH]), Balken über ihre Endknoten und Geräte über
     * Typ, Balken, Parameter und Seite den Teilen der Startfestung [base] zu. Ein Teil mit Zuordnung ist
     * "vorhanden" (die Startfestung enthält es schon), eines ohne ist neu.
     */
    private fun overlay(plan: BlueprintDef, base: BlueprintDef): Overlay {
        val nodes = IntArray(plan.nodes.size) { i ->
            val n = plan.nodes[i]
            base.nodes.indexOfFirst { kotlin.math.abs(it.x - n.x) <= NODE_MATCH && kotlin.math.abs(it.y - n.y) <= NODE_MATCH }
        }
        val beams = IntArray(plan.beams.size) { i ->
            val b = plan.beams[i]
            val a = nodes.getOrElse(b.a) { -1 }; val e = nodes.getOrElse(b.b) { -1 }
            if (a < 0 || e < 0) -1 else base.beams.indexOfFirst { (it.a == a && it.b == e) || (it.a == e && it.b == a) }
        }
        val devices = IntArray(plan.devices.size) { i ->
            val d = plan.devices[i]
            val pb = plan.beams.getOrNull(d.beam)
            val bi = beams.getOrElse(d.beam) { -1 }
            if (pb == null || bi < 0) -1 else {
                val bb = base.beams[bi]
                val swapped = nodes[pb.a] != bb.a // gleicher Balken, andere Laufrichtung: t und Seite spiegeln
                val t = if (swapped) 1f - d.t else d.t
                val neg = if (swapped) !d.sideNegative else d.sideNegative
                base.devices.indexOfFirst { it.type == d.type && it.beam == bi && kotlin.math.abs(it.t - t) <= EPS && it.sideNegative == neg }
            }
        }
        return Overlay(nodes, beams, devices)
    }

    /** Startfestungen, auf die KI-Pläne aufsetzen: die von Karten verwendeten (sonst alle mit Tag `start`). */
    private fun baseForts(p: ContentPack, ctx: Ctx): List<BlueprintDef> {
        val used = p.maps.flatMap { m -> m.startForts.mapNotNull { ctx.blueprints[it.blueprint] } }.filter { "ai" !in it.tags }.distinctBy { it.id }
        return used.ifEmpty { p.blueprints.filter { "start" in it.tags } }
    }

    private fun checkAiPlans(p: ContentPack, ctx: Ctx, c: Collector) {
        val plans = p.blueprints.filter { "ai" in it.tags }
        for (diff in DIFFICULTIES) {
            val n = plans.count { diff in it.tags }
            if (n != 1) c.add(ContentRule.AI_PLAN, "difficulty '$diff'", "needs exactly one ai blueprint, found $n")
        }
        val bases = baseForts(p, ctx)
        for (bp in plans) {
            val w = "ai blueprint '${bp.id}'"
            if (bp.tags.count { it in DIFFICULTIES } != 1) c.add(ContentRule.AI_PLAN, w, "needs exactly one of $DIFFICULTIES in tags")
            // Teile, die die Startfestung schon enthält, dürfen in keinem Schritt stehen (Vereinigung über alle Basen)
            val beamPre = BooleanArray(bp.beams.size) { false }
            val devPre = BooleanArray(bp.devices.size) { false }
            for (base in bases) {
                val ov = overlay(bp, base)
                checkAgainstBase(bp, base, ov, p, ctx, c)
                for (i in beamPre.indices) if (ov.beams[i] >= 0) beamPre[i] = true
                for (i in devPre.indices) if (ov.devices[i] >= 0) devPre[i] = true
            }
            checkSteps(bp, beamPre, devPre, ctx, c)
        }
    }

    /** Passt der Plan [bp] auf die Startfestung [base]? (Material, Geräteabstand, verankerte Knoten) */
    private fun checkAgainstBase(bp: BlueprintDef, base: BlueprintDef, ov: Overlay, p: ContentPack, ctx: Ctx, c: Collector) {
        val w = "ai blueprint '${bp.id}' on '${base.id}'"
        for ((i, n) in bp.nodes.withIndex()) {
            val bi = ov.nodes[i]
            if (bi >= 0 && base.nodes[bi].anchored != n.anchored) c.add(ContentRule.AI_PLAN, w, "node $i at (${n.x}, ${n.y}) is anchored=${n.anchored} but anchored=${base.nodes[bi].anchored} in the start fort")
        }
        for ((i, b) in bp.beams.withIndex()) {
            val bi = ov.beams[i]
            if (bi >= 0 && base.beams[bi].material != b.material) {
                c.add(ContentRule.AI_PLAN, w, "beam $i is '${b.material}' but the start fort has '${base.beams[bi].material}' at the same position (a built beam cannot change material)")
            }
        }
        // Neue Geräte: eindeutig? Abstand zu den Geräten der Startfestung?
        val baseMounts = base.devices.map { mountPoint(base, it, ctx) }
        for ((i, d) in bp.devices.withIndex()) {
            if (ov.devices[i] >= 0) continue
            val dev = ctx.devices[d.type] ?: continue
            if (dev.unique) c.add(ContentRule.AI_PLAN, w, "device $i ('${d.type}') is unique but not part of the start fort")
            val m = mountPoint(bp, d, ctx) ?: continue
            for ((j, bm) in baseMounts.withIndex()) {
                if (bm == null) continue
                val dist = kotlin.math.hypot(m[0] - bm[0], m[1] - bm[1])
                val min = spacingOf(dev, ctx.devices[base.devices[j].type])
                if (dist < min) c.add(ContentRule.AI_PLAN, w, "device $i ('${d.type}') is $dist m from start fort device $j ('${base.devices[j].type}'), minimum $min m")
            }
        }
        // Verankerte neue Knoten brauchen ein Fundament (Command.PlaceBeam kann keine verankerten Knoten erzeugen)
        for ((i, n) in bp.nodes.withIndex()) {
            if (!n.anchored || ov.nodes[i] >= 0) continue
            for (m in p.maps) for (f in m.startForts) {
                if (f.blueprint != base.id) continue
                val wx = if (f.mirror) f.originX - n.x else f.originX + n.x
                val ok = m.foundations.any { it.owner == f.owner && kotlin.math.abs(it.x - wx) <= NODE_MATCH }
                if (!ok) c.add(ContentRule.AI_PLAN, w, "anchored node $i at x=${n.x} has no foundation on map '${m.id}' for player ${f.owner} (world x=$wx)")
            }
        }
    }

    /** Schrittfolge: Vollständigkeit der neuen Teile, Phasenreihenfolge, Techvoraussetzungen, keine vorhandenen/eindeutigen Teile. */
    private fun checkSteps(bp: BlueprintDef, beamPre: BooleanArray, devPre: BooleanArray, ctx: Ctx, c: Collector) {
        val w = "ai blueprint '${bp.id}'"
        if (bp.steps.isEmpty()) { c.add(ContentRule.AI_PLAN, w, "has no steps"); return }
        var lastPhase = -1
        val beamPos = IntArray(bp.beams.size) { -1 }
        val devPos = IntArray(bp.devices.size) { -1 }
        var pos = 0
        // Position = (Schritt, Art, Index) in Bau-Reihenfolge: Balken vor Geräten innerhalb eines Schritts
        val techAt = HashMap<String, Int>()
        for (s in bp.steps) {
            val ph = PHASES.indexOf(s.phase)
            if (ph < 0) c.add(ContentRule.AI_PLAN, w, "unknown phase '${s.phase}'")
            else if (ph <= lastPhase) c.add(ContentRule.AI_PLAN, w, "phase '${s.phase}' out of order or repeated")
            lastPhase = maxOf(lastPhase, ph)
            for (bi in s.beams) {
                if (bi !in bp.beams.indices) { c.add(ContentRule.AI_PLAN, w, "step '${s.phase}' references missing beam $bi"); continue }
                if (beamPos[bi] >= 0) c.add(ContentRule.AI_PLAN, w, "beam $bi appears in more than one step")
                if (beamPre[bi]) c.add(ContentRule.AI_PLAN, w, "beam $bi is already part of the start fort and must not be in a step")
                beamPos[bi] = pos++
            }
            for (di in s.devices) {
                if (di !in bp.devices.indices) { c.add(ContentRule.AI_PLAN, w, "step '${s.phase}' references missing device $di"); continue }
                if (devPos[di] >= 0) c.add(ContentRule.AI_PLAN, w, "device $di appears in more than one step")
                if (devPre[di]) c.add(ContentRule.AI_PLAN, w, "device $di ('${bp.devices[di].type}') is already part of the start fort and must not be in a step")
                if (ctx.devices[bp.devices[di].type]?.unique == true) c.add(ContentRule.AI_PLAN, w, "unique device $di ('${bp.devices[di].type}') must not be in a step")
                devPos[di] = pos++
            }
        }
        for (i in bp.beams.indices) if (beamPos[i] < 0 && !beamPre[i]) c.add(ContentRule.AI_PLAN, w, "beam $i is new but in no step")
        for (i in bp.devices.indices) if (devPos[i] < 0 && !devPre[i]) c.add(ContentRule.AI_PLAN, w, "device $i is new but in no step")
        // Reihenfolge: Gerät nach seinem Balken; Material/Gerät erst nach dem Techgebäude, das es freischaltet.
        // Teile der Startfestung stehen von Anfang an (Position −1) und gelten als früher als jeder Schritt.
        for ((i, d) in bp.devices.withIndex()) {
            val dev = ctx.devices[d.type] ?: continue
            if (devPos[i] < 0) { dev.grantsTech?.let { g -> techAt[g] = -1 }; continue }
            if (d.beam in bp.beams.indices && beamPos[d.beam] > devPos[i]) c.add(ContentRule.AI_PLAN, w, "device $i is built before its beam ${d.beam}")
            dev.grantsTech?.let { g -> techAt.merge(g, devPos[i]) { a, b -> minOf(a, b) } }
        }
        fun techBefore(req: String?, at: Int, what: String) {
            if (req == null || at < 0) return
            val g = techAt[req]
            if (g == null || g >= at) c.add(ContentRule.AI_PLAN, w, "$what needs tech '$req' which is not built earlier")
        }
        for ((i, b) in bp.beams.withIndex()) techBefore(ctx.materials[b.material]?.requiresTech, beamPos[i], "beam $i (${b.material})")
        for ((i, d) in bp.devices.withIndex()) techBefore(ctx.devices[d.type]?.requiresTech, devPos[i], "device $i (${d.type})")
    }
}
