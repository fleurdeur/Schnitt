package app.absentmotion.effects

/**
 * Bridges an imported Alight Motion effect to a Schnitt shader with the same
 * purpose. The original Alight UUID stays on the effect (so imported scenes and
 * links keep resolving), while the shader and its parameter names are rewritten
 * to the Alight parameter names. That keeps the sliders, keyframes and the
 * imported XML usable instead of falling back to a flat placeholder colour.
 */
internal object EffectAlias {
    private val Explicit = mapOf(
        "bevel" to "smoothbevel",
        "bevels" to "smoothbevel",
        "colortune" to "colorbalance",
        "colour tune" to "colorbalance",
        "omino diffusion" to "ominodiffusion",
        "omino-diffusion" to "ominodiffusion",
        "streak strips" to "contourstrips",
        "streakstrips" to "contourstrips",
        "streaks" to "linearstreaks",
        "streaks spin" to "spinstreaks",
        "streaksspin" to "spinstreaks",
        "streaks zoom" to "zoomstreaks",
        "streakszoom" to "zoomstreaks",
        "swirl" to "twirl",
        "zoom blur" to "radialblur",
        "zoomblur" to "radialblur",
        "pallete map" to "palettemap",
        "halftonedots" to "halftone",
        "halftonedotscmyk" to "cmykhalftone",
        "halftonecmyk" to "cmykhalftone",
        "pinchbulge" to "bulge",
        "pinchbulge2" to "bulge",
        "pinchbulgeinside" to "innerbulge",
        "tile" to "tiles",
        "motionblur" to "directionalblur",
        "mblur" to "directionalblur",
        "motionblur2" to "directionalblur",
        "motionblur3" to "directionalblur",
        "motionblur4" to "directionalblur",
    )

    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    fun bind(def: EffectDefinition, builtIns: List<EffectDefinition>): EffectDefinition {
        if (!def.id.startsWith("com.alightcreative.")) return def
        val native = builtIns.filter { !it.id.startsWith("com.alightcreative.") && it.key != "foreign" }
        val byKey = native.associateBy { it.key }
        val byName = native.associateBy { slug(it.name) }
        val stem = slug(def.id.substringAfterLast('.'))
        val target = Explicit[slug(def.name)]?.let(byKey::get)
            ?: Explicit[stem]?.let(byKey::get)
            ?: byKey[stem]
            ?: byName[slug(def.name)]
            ?: return def
        if (target.sksl == null && target.passes.isEmpty()) return def
        if (target.params.any { it.id in setOf("size", "time", "duration", "resolution") }) return def

        val byId = def.params.associateBy { it.id }
        val used = HashSet<String>()
        val rename = LinkedHashMap<String, String>()
        val extra = ArrayList<EffectParam>()

        for (param in target.params) {
            val exact = byId[param.id]?.takeIf { it.id !in used && compatible(param, it) }
            val match = exact ?: candidates(param)
                .asSequence()
                .mapNotNull(byId::get)
                .firstOrNull { it.id !in used && compatible(param, it) }
            if (match != null) {
                rename[param.id] = match.id
                used += match.id
                continue
            }
            var id = param.id
            while (def.params.any { it.id == id } || extra.any { it.id == id }) id += "2"
            rename[param.id] = id
            extra += param.copy(id = id, label = "${param.label} (Schnitt)")
        }

        val params = def.params + extra
        return def.copy(
            params = params,
            sksl = target.sksl?.let { rewrite(it, rename) },
            passes = target.passes.map { it.copy(sksl = rewrite(it.sksl, rename)) },
            fallback = target.sksl?.let { rewrite(it, rename) } ?: def.fallback,
            description = "${def.description} · Schnitt ${target.name} engine",
            native = false,
            status = "ok",
            alsoIn = listOf("external"),
            page = def.key,
        )
    }

    private val ExplicitParam = mapOf(
        "punch" to listOf("punchout", "punch"),
        "screenSpace" to listOf("screenSpace", "screen", "screenspace"),
        "blockSize" to listOf("size", "blockSize", "blocksize"),
        "blurLength" to listOf("length", "blurLength", "blur", "amount"),
        "sizing" to listOf("sizing", "size", "scale", "mode"),
        "reach" to listOf("reach", "length", "distance", "amount"),
        "center" to listOf("center", "position", "origin", "light"),
        "offset" to listOf("offset", "position", "center", "origin"),
        "position" to listOf("position", "offset", "center", "origin"),
        "stretch" to listOf("stretch", "scale", "aspect"),
        "zoom" to listOf("zoom", "scale", "size"),
        "color" to listOf("color", "tint", "color1"),
        "color1" to listOf("color1", "color", "tint"),
        "color2" to listOf("color2", "color", "tint"),
    )

    private fun candidates(param: EffectParam): List<String> {
        val explicit = ExplicitParam[param.id].orEmpty()
        val generic = when (param.kind) {
            "point" -> listOf("center", "offset", "position", "origin", "light", "from", "to")
            "color" -> listOf("color", "color1", "color2", "color3", "tint", "ink", "paper", "background", "key")
            "angle" -> listOf("angle", "rotation", "direction")
            "switch", "bool" -> listOf("invert", "mirror", "mono", "freeze", "colored", "fill", "show")
            "choice" -> listOf("mode", "style", "pattern", "blend", "shape", "direction", "source", "axis")
            else -> listOf(
                "strength", "amount", "intensity", "blur", "radius", "size", "blockSize", "blurLength", "reach",
                "scale", "spread", "alpha", "opacity", "feather", "threshold", "phase", "seed", "evolution",
                "count", "segments", "stretch", "offset", "zoom", "frequency", "speed", "width", "height",
            )
        }
        return explicit + generic
    }

    private fun compatible(target: EffectParam, candidate: EffectParam): Boolean {
        val a = target.kind
        val b = candidate.kind
        return when {
            a == b -> true
            a in setOf("float", "angle") && b in setOf("float", "angle") -> true
            a in setOf("switch", "bool") && b in setOf("switch", "bool") -> true
            a == "choice" && b in setOf("float", "choice") -> true
            a == "point" && b in setOf("float", "angle") -> true
            else -> false
        }
    }

    private fun rewrite(source: String, rename: Map<String, String>): String {
        var text = source
        // The image size and clock are engine probes, not user parameters. Prefix
        // them so an Alight parameter called size/time cannot collide with them.
        text = Regex("""\bsize\b""").replace(text, "amSize")
        text = Regex("""\bresolution\b""").replace(text, "amSize")
        text = Regex("""\btime\b""").replace(text, "amTime")
        text = Regex("""\bduration\b""").replace(text, "amDuration")
        if (rename.isEmpty()) return text
        val pattern = rename.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("""\b(?:$pattern)\b""").replace(text) { rename[it.value] ?: it.value }
    }
}
