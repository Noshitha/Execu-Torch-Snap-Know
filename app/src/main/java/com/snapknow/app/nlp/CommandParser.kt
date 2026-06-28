package com.snapknow.app.nlp

import android.util.Log

private const val TAG = "CommandParser"

// ─── Sealed command hierarchy ─────────────────────────────────────────────────

sealed class Command {
    data class StoreObject(val objectName: String, val location: String) : Command()
    data class QueryObject(val objectName: String) : Command()
    data class StoreFace(val name: String, val relationship: String) : Command()
    object QueryFace : Command()
    object ListMemories : Command()
    data class ForgetObject(val objectName: String) : Command()
    data class ForgetFace(val name: String) : Command()
    data class Unknown(val rawText: String) : Command()
}

// ─── Parser ───────────────────────────────────────────────────────────────────

object CommandParser {

    data class FaceDetails(
        val name: String,
        val relationship: String = "",
        val notes: String = ""
    )

    // Prepositions — included IN the location capture so "on the table" is stored, not just "the table"
    private val LOC_PREPS = "on top of|next to|beside the|on the|by the|to the|on|in|at|under|near|behind|beside|inside|outside|above|below"

    // ── Store Object ──────────────────────────────────────────────────────────
    private val STORE_OBJ_PATTERNS = listOf(

        // 1. "I am / I'm / I have / I've putting/keeping/placing/leaving my keys on the table"
        Regex(
            """i(?:'m| am| have| 've) (?:put|putting|kept|keeping|placed|placing|left|leaving|set|setting|stored|storing) (?:my |the )?(.+?) ((?:$LOC_PREPS) .+)""",
            RegexOption.IGNORE_CASE
        ),

        // 2. "I kept / I put / I left my keys on the table"  (simple past, no auxiliary)
        Regex(
            """i (?:put|kept|left|placed|set|stored|have put|have kept|have left) (?:my |the )?(.+?) ((?:$LOC_PREPS) .+)""",
            RegexOption.IGNORE_CASE
        ),

        // 3. "my keys are on the table" / "the phone is on the counter"
        Regex(
            """(?:my |the )?(.+?) (?:is|are) ((?:$LOC_PREPS) .+)""",
            RegexOption.IGNORE_CASE
        ),

        // 4. "remember my keys are on the right side"
        Regex(
            """remember[,:]? (?:my |the )?(.+?) (?:is|are|will be) ((?:$LOC_PREPS) .+)""",
            RegexOption.IGNORE_CASE
        ),

        // 5. "put / keep / leave / place the keys on the table"  (imperative / bare verb)
        Regex(
            """(?:put|keep|leave|place|store) (?:my |the )?(.+?) ((?:$LOC_PREPS) .+)""",
            RegexOption.IGNORE_CASE
        ),

        // 6. "save keys on the table" / "save: keys → kitchen"
        Regex(
            """save[,:]? (?:my )?(.+?) (?:→ ?|(?:is|are) )((?:$LOC_PREPS)? ?.+)""",
            RegexOption.IGNORE_CASE
        )
    )

    // ── Query Object ──────────────────────────────────────────────────────────
    private val QUERY_OBJ_PATTERNS = listOf(
        Regex("""where (?:is|are) (?:my |the )?(.+?)[?.]?$""", RegexOption.IGNORE_CASE),
        Regex("""where did i (?:put|leave|place|keep|store|last (?:put|leave|see)) (?:my |the )?(.+?)[?.]?$""", RegexOption.IGNORE_CASE),
        Regex("""(?:find|help me find|locate|tell me where(?:'s| is)) (?:my |the )?(.+?)[?.]?$""", RegexOption.IGNORE_CASE),
        Regex("""(?:can you find|do you know where) (?:my |the )?(.+?) (?:is|are)[?.]?$""", RegexOption.IGNORE_CASE),
        Regex("""i (?:lost|can't find|cannot find) (?:my |the )?(.+?)[?.]?$""", RegexOption.IGNORE_CASE),
        Regex("""(?:what about|where(?:'s| is)) (?:my |the )?(.+?)[?.]?$""", RegexOption.IGNORE_CASE)
    )

    private const val NAME_PATTERN = """[A-Za-z][A-Za-z'\-]*(?:\s+[A-Za-z][A-Za-z'\-]*){0,2}"""
    private const val REL_PATTERN = """[A-Za-z][A-Za-z'\-]*(?:\s+[A-Za-z][A-Za-z'\-]*){0,3}"""

    // ── Store Face ────────────────────────────────────────────────────────────
    private val FACE_INTRO_PATTERNS = listOf(
        Regex(
            """^(?:this|that|it)(?:'s| is)\s+($NAME_PATTERN)(?:\s*(?:,|and)\s*(?:he|she|they)\s+(?:is|'s)\s+(?:my\s+)?($REL_PATTERN))?[.!?]?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^(?:this|that) is\s+($NAME_PATTERN)\s*,\s*(?:my\s+)?($REL_PATTERN)[.!?]?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^(?:his|her|their)\s+name\s+is\s+($NAME_PATTERN)(?:\s*(?:,|and)\s*(?:he|she|they)\s+(?:is|'s)\s+(?:my\s+)?($REL_PATTERN))?[.!?]?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^remember\s+(?:this person[,:]?\s*)?($NAME_PATTERN)(?:[,.]?\s+(?:my\s+)?($REL_PATTERN))?[.!?]?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^($NAME_PATTERN)\s+(?:is|'s)\s+my\s+($REL_PATTERN)[.!?]?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^(?:meet|this person is)\s+($NAME_PATTERN)[.!?]?$""",
            RegexOption.IGNORE_CASE
        )
    )

    // ── Query Face ────────────────────────────────────────────────────────────
    private val QUERY_FACE_PATTERNS = listOf(
        Regex("""who is (?:this|that)(?: person)?[?]?""", RegexOption.IGNORE_CASE),
        Regex("""do (?:you |i )?know (?:this|that)(?: person)?[?]?""", RegexOption.IGNORE_CASE),
        Regex("""what(?:'s| is) (?:his|her|their) name[?]?""", RegexOption.IGNORE_CASE),
        Regex("""(?:identify|recogni[sz]e) (?:this|that)(?: person| face)?[?]?""", RegexOption.IGNORE_CASE),
        Regex("""have (?:i|we) met (?:this|that)(?: person)?(?: before)?[?]?""", RegexOption.IGNORE_CASE)
    )

    // ── List Memories ─────────────────────────────────────────────────────────
    private val LIST_PATTERNS = listOf(
        Regex("""what do you remember[?]?""", RegexOption.IGNORE_CASE),
        Regex("""(?:list|show|tell me)(?: all)? (?:my )?memories[?]?""", RegexOption.IGNORE_CASE),
        Regex("""what (?:have you|did you) save[d]?[?]?""", RegexOption.IGNORE_CASE),
        Regex("""(?:what do i have|what(?:'s| is) stored)[?]?""", RegexOption.IGNORE_CASE)
    )

    // ── Forget ────────────────────────────────────────────────────────────────
    private val FORGET_OBJ  = Regex("""forget (?:my |the )?(.+)""", RegexOption.IGNORE_CASE)
    private val FORGET_FACE = Regex("""forget (?:person |who is )?(\w+(?:\s\w+)?)""", RegexOption.IGNORE_CASE)

    // ─────────────────────────────────────────────────────────────────────────

    fun parse(input: String): Command {
        val text = input.trim()
        Log.d(TAG, "Parsing: '$text'")

        // 1. Store face?
        parseFaceDetails(text)?.let { details ->
            Log.d(TAG, "StoreFace: name=${details.name} rel=${details.relationship}")
            return Command.StoreFace(details.name, details.relationship)
        }

        // 2. Query face?
        QUERY_FACE_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(text)) {
                Log.d(TAG, "QueryFace")
                return Command.QueryFace
            }
        }

        // 3. Store object?
        STORE_OBJ_PATTERNS.forEach { pattern ->
            pattern.find(text)?.let { m ->
                val obj = m.groupValues[1].trim().removePossessive()
                val loc = m.groupValues[2].trim().trimEnd('.')
                if (obj.isNotBlank() && loc.isNotBlank()) {
                    Log.d(TAG, "StoreObject: '$obj' → '$loc'")
                    return Command.StoreObject(obj, loc)
                }
            }
        }

        // 4. Query object?
        QUERY_OBJ_PATTERNS.forEach { pattern ->
            pattern.find(text)?.let { m ->
                val obj = m.groupValues[1].trim().removePossessive()
                if (obj.isNotBlank()) {
                    Log.d(TAG, "QueryObject: '$obj'")
                    return Command.QueryObject(obj)
                }
            }
        }

        // 5. List memories?
        LIST_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(text)) return Command.ListMemories
        }

        // 6. Forget?
        FORGET_OBJ.find(text)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.split(" ").all { it.firstOrNull()?.isUpperCase() == true }) {
                return Command.ForgetFace(target)
            }
            return Command.ForgetObject(target)
        }

        return Command.Unknown(text)
    }

    fun parseFaceDetails(input: String, allowBareName: Boolean = false): FaceDetails? {
        val text = input.normaliseWhitespace().trimEnd('.', '!', '?')
        if (text.isBlank()) return null

        FACE_INTRO_PATTERNS.forEach { pattern ->
            pattern.matchEntire(text)?.let { match ->
                val name = match.groupValues.getOrNull(1).orEmpty().toDisplayName()
                val relationship = match.groupValues.getOrNull(2).orEmpty().normaliseRelationship()
                if (name.isNotBlank()) {
                    return FaceDetails(
                        name = name,
                        relationship = relationship,
                        notes = buildFaceNotes(text, relationship)
                    )
                }
            }
        }

        if (allowBareName && text.matches(Regex("""^$NAME_PATTERN$""", RegexOption.IGNORE_CASE))) {
            return FaceDetails(name = text.toDisplayName())
        }

        return null
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildFaceNotes(rawInput: String, relationship: String): String {
        if (relationship.isBlank()) return ""
        val lowered = rawInput.lowercase()
        val marker = "my ${relationship.lowercase()}"
        val relationshipIndex = lowered.indexOf(marker)
        if (relationshipIndex == -1) return ""
        val trailing = rawInput.substring(relationshipIndex + marker.length)
            .trim(' ', ',', '.', '!', '?')
        return trailing
    }

    private fun String.capitalise() =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun String.normaliseWhitespace() =
        replace(Regex("""\s+"""), " ").trim()

    private fun String.toDisplayName() =
        normaliseWhitespace()
            .replace(Regex("""^(?:this is|that is|it is|it's|his name is|her name is|their name is)\s+""", RegexOption.IGNORE_CASE), "")
            .capitalise()

    private fun String.normaliseRelationship() =
        normaliseWhitespace()
            .replace(Regex("""^(?:my|the)\s+""", RegexOption.IGNORE_CASE), "")
            .trim(',', '.', '!', '?')
            .lowercase()

    private fun String.removePossessive() =
        replace(Regex("""'s$"""), "").trim()
}
