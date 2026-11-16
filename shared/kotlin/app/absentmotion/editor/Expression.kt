package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withKeyframes
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small, safe expression language for keyframes.  Applying an expression
 * bakes ordinary keyframes, so the engine, export and every importer keep using
 * the exact same XML they already understand.
 */
internal object Expressions {
    private class Vars(val time: Float, val t: Float, val frame: Int, val fps: Int, val width: Int, val height: Int, val index: Int, val value: Float)

    private sealed interface Node { fun eval(v: Vars): Float }

    private class Const(val value: Float) : Node { override fun eval(v: Vars) = value }
    private class Var(val name: String) : Node {
        override fun eval(v: Vars): Float = when (name) {
            "time", "seconds" -> v.time
            "t", "progress" -> v.t
            "frame" -> v.frame.toFloat()
            "fps" -> v.fps.toFloat()
            "width" -> v.width.toFloat()
            "height" -> v.height.toFloat()
            "index", "component" -> v.index.toFloat()
            "value" -> v.value
            "pi" -> Math.PI.toFloat()
            else -> 0f
        }
    }
    private class Unary(val op: Char, val a: Node) : Node {
        override fun eval(v: Vars)= when (op) { '-' -> -a.eval(v); '+' -> a.eval(v); else -> if (a.eval(v) == 0f) 1f else 0f }
    }
    private class Binary(val op: String, val a: Node, val b: Node) : Node {
        override fun eval(v: Vars): Float {
            val x = a.eval(v); val y = b.eval(v)
            return when (op) {
                "+" -> x + y; "-" -> x - y; "*" -> x * y; "/" -> if (abs(y) < 1e-12f) 0f else x / y
                "%" -> if (abs(y) < 1e-12f) 0f else x % y; "^" -> x.pow(y)
                "<" -> if (x < y) 1f else 0f; "<=" -> if (x <= y) 1f else 0f
                ">" -> if (x > y) 1f else 0f; ">=" -> if (x >= y) 1f else 0f
                "==" -> if (abs(x - y) < 1e-6f) 1f else 0f; "!=" -> if (abs(x - y) >= 1e-6f) 1f else 0f
                "&&" -> if (x != 0f && y != 0f) 1f else 0f; "||" -> if (x != 0f || y != 0f) 1f else 0f
                else -> 0f
            }
        }
    }
    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(v: Vars): Float {
            val a = args.map { it.eval(v) }
            fun at(i: Int) = a.getOrElse(i) { 0f }
            return when (name) {
                "sin" -> sin(at(0)); "cos" -> cos(at(0)); "tan" -> tan(at(0))
                "asin" -> asin(at(0).coerceIn(-1f, 1f)); "acos" -> acos(at(0).coerceIn(-1f, 1f)); "atan" -> atan(at(0))
                "abs" -> abs(at(0)); "floor" -> floor(at(0)); "ceil" -> ceil(at(0)); "round" -> round(at(0)); "sqrt" -> sqrt(max(0f, at(0)))
                "pow" -> at(0).pow(at(1)); "exp" -> Math.exp(at(0).toDouble()).toFloat(); "log" -> ln(max(1e-12f, at(0)))
                "min" -> a.minOrNull() ?: 0f; "max" -> a.maxOrNull() ?: 0f
                "clamp" -> at(0).coerceIn(min(at(1), at(2)), max(at(1), at(2)))
                "lerp", "mix" -> at(0) + (at(1) - at(0)) * at(2)
                "step" -> if (at(1) >= at(0)) 1f else 0f
                "smoothstep" -> {
                    val u = if (abs(at(2) - at(1)) < 1e-9f) 0f else ((at(0) - at(1)) / (at(2) - at(1))).coerceIn(0f, 1f)
                    u * u * (3f - 2f * u)
                }
                "random" -> hash(at(0).toDouble() + at(1).toDouble() * 17.0 + at(2).toDouble() * 131.0)
                "noise" -> noise(at(0), at(1))
                "wiggle" -> {
                    val freq = at(0).takeIf { abs(it) > 1e-6f } ?: 1f
                    val s = v.time * freq * 6.2831855f
                    (sin(s) * 0.65f + sin(s * 2.31f + 1.7f) * 0.35f) * at(1)
                }
                "if" -> if (at(0) != 0f) at(1) else at(2)
                else -> 0f
            }
        }
    }

    private fun hash(x: Double): Float {
        var h = x.toLong() * -7046029254386353131L
        h = h xor (h ushr 33)
        h *= -4658895280553007687L
        h = h xor (h ushr 29)
        return ((h ushr 40).toInt() and 0xFFFFFF) / 16777215f
    }

    private fun noise(x: Float, seed: Float): Float {
        val i = floor(x).toInt(); val f = x - i
        val u = f * f * (3f - 2f * f)
        return hash(i.toDouble() + seed * 71.0) * (1f - u) + hash((i + 1).toDouble() + seed * 71.0) * u
    }

    private class Parser(val text: String) {
        var i = 0
        fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun peek(): Char { skip(); return if (i < text.length) text[i] else '\u0000' }
        fun take(): Char { skip(); return if (i < text.length) text[i++] else '\u0000' }
        fun parse(): Node { val n = expr(); skip(); return n }
        fun expr(): Node {
            var left = term()
            while (true) {
                val op = when { match("||") -> "||"; match("&&") -> "&&"; else -> null } ?: break
                left = Binary(op, left, term())
            }
            return left
        }
        fun term(): Node {
            var left = compare()
            while (true) {
                val op = when { match("+") -> "+"; match("-") -> "-"; else -> null } ?: break
                left = Binary(op, left, compare())
            }
            return left
        }
        fun compare(): Node {
            var left = sum()
            while (true) {
                val op = when {
                    match(">=") -> ">="; match("<=") -> "<="; match("==") -> "=="; match("!=") -> "!="
                    match(">") -> ">"; match("<") -> "<"; else -> null
                } ?: break
                left = Binary(op, left, sum())
            }
            return left
        }
        fun sum(): Node {
            var left = factor()
            while (true) {
                val op = when { match("*") -> "*"; match("/") -> "/"; match("%") -> "%"; else -> null } ?: break
                left = Binary(op, left, factor())
            }
            return left
        }
        fun factor(): Node {
            var left = unary()
            if (match("^")) left = Binary("^", left, factor())
            return left
        }
        fun unary(): Node {
            val c = peek()
            if (c == '-' || c == '+' || c == '!') { take(); return Unary(c, unary()) }
            return primary()
        }
        fun primary(): Node {
            if (match("(")) { val n = expr(); match(")"); return n }
            val c = peek()
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val name = text.substring(start, i).lowercase()
                if (match("(")) {
                    val args = ArrayList<Node>()
                    if (peek() != ')') {
                        args += expr()
                        while (match(",")) args += expr()
                    }
                    match(")")
                    return Call(name, args)
                }
                return Var(name)
            }
            take()
            return Const(0f)
        }
        fun number(): Node {
            val start = i
            while (i < text.length) {
                val c = text[i]
                val exponentSign = (c == '-' || c == '+') && i > start && (text[i - 1] == 'e' || text[i - 1] == 'E')
                if (!(c.isDigit() || c == '.' || c == 'e' || c == 'E' || exponentSign)) break
                i++
            }
            return Const(text.substring(start, i).toFloatOrNull() ?: 0f)
        }
        fun match(s: String): Boolean {
            skip()
            if (!text.regionMatches(i, s, 0, s.length)) return false
            if (s.all { it.isLetter() } && i + s.length < text.length && text[i + s.length].isLetterOrDigit()) return false
            i += s.length
            return true
        }
    }

    internal fun parts(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0; var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { out += text.substring(start, i); start = i + 1 }
            }
        }
        out += text.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("0") }
    }

    fun evaluate(text: String, time: Float, t: Float, frame: Int, fps: Int, width: Int, height: Int, index: Int, value: Float): Float =
        runCatching { Parser(text).parse().eval(Vars(time, t, frame, fps, width, height, index, value)) }.getOrDefault(0f)
}

/** Replaces a property with baked expression keyframes and stores the source text. */
internal fun Scene.bakeExpression(layerId: Long, ref: PropRef, text: String, label: String = ""): Scene {
    val layer = layer(layerId) ?: return this
    val source = Expressions.parts(text)
    val element = layer.element(ref) ?: return this
    val existing = (element["value"] ?: "0").split(',').mapNotNull { it.trim().finiteFloat() }
    val count = max(source.size, existing.size).coerceAtLeast(1)
    val start = layer.startTime
    val end = layer.endTime
    val span = (end - start).coerceAtLeast(1)
    val keys = ArrayList<Keyframe>()
    for (frame in start..end) {
        val t = (frame - start).toFloat() / span
        val values = FloatArray(count) { i ->
            val raw = source.getOrElse(i) { source.last() }
            Expressions.evaluate(raw, (frame - start) / 1000f, t, frame, fps, width, height, i, existing.getOrElse(i) { 0f })
        }
        keys += Keyframe(t, formatFloats(values))
    }
    val expression = XmlNode("expression", linkedMapOf("value" to text).also { if (label.isNotBlank()) it["label"] = label })
    val next = element.copy(children = element.children.filterNot { it.name == "expression" } + expression).withKeyframes(keys)
    return update(layerId) { it.withElement(ref, next) }
}

internal fun Layer.expressionText(ref: PropRef): String? =
    element(ref)?.children?.firstOrNull { it.name == "expression" }?.get("value")
