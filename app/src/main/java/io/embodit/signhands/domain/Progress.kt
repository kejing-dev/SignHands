package io.embodit.signhands.domain

import org.json.JSONObject

/** One letter's review record (design `dataModel.progress`, `srs`). [due] is an epoch day; box 0 = never learned. */
data class LetterRecord(val box: Int = 0, val due: Long = 0L, val seen: Int = 0, val correct: Int = 0, val wrong: Int = 0)

data class LessonRecord(val done: Boolean = false, val quizScore: Int = -1)

data class Progress(
    val letters: Map<String, LetterRecord> = emptyMap(),
    val lessons: Map<Int, LessonRecord> = emptyMap(),
    val lastLesson: Int = 0,
    /** Letters followed correctly at least once (outside the quiz): "learned" for the lesson entry rule. */
    val followed: Set<String> = emptySet(),
    /** v0.3.0: epoch days with at least one sign done right (the home page's streak). */
    val activeDays: Set<Long> = emptySet(),
) {
    fun record(l: String): LetterRecord = letters[l] ?: LetterRecord()

    /** v0.3.0: days in a row with practice, ending today (or yesterday: today's practice has not happened yet). */
    fun streak(today: Long): Int {
        var d = if (today in activeDays) today else today - 1
        var n = 0
        while (d in activeDays) {
            n++
            d--
        }
        return n
    }

    /** v0.3.0: letters followed right at least once (A-Z), word signs followed right at least once. */
    val lettersLearned: Int
        get() = followed.count { it.length == 1 }
    val wordsLearned: Int
        get() = followed.count { it.startsWith("w:") }

    fun practised(today: Long): Progress = if (today in activeDays) this else copy(activeDays = activeDays + today)

    fun due(today: Long): List<String> = letters.filter { (_, r) -> r.box > 0 && r.due <= today }.keys.sorted()

    fun toJson(): String {
        val o = JSONObject()
        val ls = JSONObject()
        for ((k, r) in letters) ls.put(k, JSONObject().put("box", r.box).put("due", r.due).put("seen", r.seen).put("correct", r.correct).put("wrong", r.wrong))
        o.put("letters", ls)
        val le = JSONObject()
        for ((k, r) in lessons) le.put(k.toString(), JSONObject().put("done", r.done).put("quizScore", r.quizScore))
        o.put("lessons", le)
        o.put("lastLesson", lastLesson)
        o.put("followed", org.json.JSONArray(followed.sorted()))
        o.put("activeDays", org.json.JSONArray(activeDays.sorted().takeLast(400)))
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Progress {
            val o = JSONObject(s)
            val letters = HashMap<String, LetterRecord>()
            o.optJSONObject("letters")?.let { ls ->
                for (k in ls.keys()) {
                    val r = ls.getJSONObject(k)
                    letters[k] = LetterRecord(r.optInt("box"), r.optLong("due"), r.optInt("seen"), r.optInt("correct"), r.optInt("wrong"))
                }
            }
            val lessons = HashMap<Int, LessonRecord>()
            o.optJSONObject("lessons")?.let { le ->
                for (k in le.keys()) {
                    val r = le.getJSONObject(k)
                    k.toIntOrNull()?.let { lessons[it] = LessonRecord(r.optBoolean("done"), r.optInt("quizScore", -1)) }
                }
            }
            val followed = HashSet<String>()
            o.optJSONArray("followed")?.let { a -> for (i in 0 until a.length()) followed += a.getString(i) }
            val days = HashSet<Long>()
            o.optJSONArray("activeDays")?.let { a -> for (i in 0 until a.length()) days += a.getLong(i) }
            return Progress(letters, lessons, o.optInt("lastLesson"), followed, days)
        }
    }
}

/** Leitner boxes 1..5 with 1 / 3 / 7 / 14 / 30 days (design `srs.model`). */
object Srs {
    val INTERVALS = intArrayOf(1, 3, 7, 14, 30)
    const val MAX_BOX = 5
    const val REVIEW_MAX = 10

    /** After the lesson quiz: the letter enters box 1 (due tomorrow) whatever the answer; a right answer counts. */
    fun afterQuiz(r: LetterRecord, correct: Boolean, today: Long): LetterRecord =
        r.copy(box = 1, due = today + INTERVALS[0], seen = r.seen + 1, correct = r.correct + if (correct) 1 else 0, wrong = r.wrong + if (correct) 0 else 1)

    /** A review answer: right moves up a box (box 5 right = mastered, stays 5), wrong goes back to box 1. */
    fun afterReview(r: LetterRecord, correct: Boolean, today: Long): LetterRecord {
        val box = if (correct) (r.box + 1).coerceAtMost(MAX_BOX) else 1
        return r.copy(box = box, due = today + INTERVALS[box - 1], seen = r.seen + 1, correct = r.correct + if (correct) 1 else 0, wrong = r.wrong + if (correct) 0 else 1)
    }

    /** A word letter of lesson 6: counts as one review of the letter (design `lessons.wordFlow`). */
    fun afterWord(r: LetterRecord, today: Long): LetterRecord = if (r.box == 0) afterQuiz(r, true, today) else afterReview(r, true, today)

    /** Mastery dots on the alphabet: 0..5. */
    fun dots(r: LetterRecord): Int = r.box.coerceIn(0, MAX_BOX)
}
