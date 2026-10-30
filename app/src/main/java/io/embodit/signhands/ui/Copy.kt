package io.embodit.signhands.ui

import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.Lang
import java.util.Locale

/**
 * All interface copy, Chinese and English (design `i18n`, `terminology`: only "teacher's hand", "watch", "try it",
 * "right", "quiz", "review", "alphabet", "slow", "from the front"; never "ghost", "joint", "score").
 */
class Copy(val lang: Lang) {
    val en: Boolean = lang == Lang.EN || (lang == Lang.SYSTEM && Locale.getDefault().language != "zh")

    private fun t(zh: String, en: String) = if (this.en) en else zh

    val appName get() = t("手语老师", "Sign Hands")
    val loading get() = t("准备中…", "Getting ready…")
    val welcome get() = t("跟着老师的手，学会美国手语（ASL）26 个字母", "Follow the teacher's hand and learn the 26 letters of American Sign Language (ASL)")
    val notChinese get() = t("这是美国手语，不是中国手语", "This is ASL fingerspelling")
    val whichHand get() = t("你用哪只手？", "Which hand do you use?")
    val rightHand get() = t("右手", "Right hand")
    val leftHand get() = t("左手", "Left hand")

    fun raiseHand(h: Handed, lost: Boolean) =
        if (lost) t("手出了视野，举回到胸前（拿着手柄的话先放下）", "Your hand left the view. Raise it to your chest (put the controller down)")
        else if (h == Handed.RIGHT) t("把右手举到胸前（先放下手柄）", "Raise your right hand to your chest (put the controller down)")
        else t("把左手举到胸前（先放下手柄）", "Raise your left hand to your chest (put the controller down)")

    fun raiseShort(lost: Boolean) = if (lost) t("手出了视野", "Hand out of view") else t("把手举到这里", "Raise your hand here")
    val watchFirst get() = t("先看示范", "Watch first")
    val backHome get() = t("回主页", "Home")
    val trackingOffA get() = t("需要用手：在系统设置里打开手势追踪；拿着手柄的话先放下", "This needs your hands: turn on hand tracking in system settings, and put the controllers down")
    val trackingOffB get() = t("需要手势追踪权限", "Hand tracking permission is needed")
    val checkAgain get() = t("再检查一次", "Check again")
    val watchOnly get() = t("先只看示范", "Just watch for now")
    val watchOnlyBanner get() = t("手势追踪可用了？再检查一次", "Hand tracking on? Check again")

    fun reviewToday(n: Int) = t("今天复习 $n 个", "Review $n today")
    val noReview get() = t("今天没有要复习的", "Nothing to review today")
    fun lesson(n: Int) = t("第 $n 课", "Lesson $n")
    val spellWords get() = t("拼词", "Spell words")
    val alphabet get() = t("字母表", "Alphabet")
    val settings get() = t("设置", "Settings")
    val mute get() = t("静音", "Mute")
    val unmute get() = t("打开声音", "Sound on")
    val recall get() = t("叫回面前", "Bring here")
    val dragBar get() = t("捏住下面的横条可以拖动面板", "Pinch the bar below to move the panel")
    val back get() = t("返回", "Back")

    fun lessonTitle(n: Int, letter: String) = t("第 $n 课 · $letter", "Lesson $n · $letter")
    fun wordTitle(word: String, pos: Int) = t("第 6 课 · 拼 $word  ${pos + 1} / ${word.length}", "Lesson 6 · $word  ${pos + 1} / ${word.length}")
    fun freeTitle(letter: String) = t("字母表 · $letter", "Alphabet · $letter")
    val slow get() = t("慢放", "Slow")
    val opposite get() = t("从对面看", "From the front")
    val oppositeNote get() = t("这是对面的人看到的样子", "This is what the other person sees")
    val tryIt get() = t("我来试试", "Try it")
    val next get() = t("下一个 ›", "Next ›")
    val replay get() = t("重播", "Replay")
    val dragToTurn get() = t("捏住下面的圆环拖动，可以转着看", "Pinch the ring below and drag to turn it")
    val fitHint get() = t("把手指塞进老师的手里", "Slide your fingers into the teacher's hand")
    val watchAgain get() = t("再看一遍", "Watch again")
    val skip get() = t("跳过", "Skip")
    val pinchAgain get() = t("再捏一次确认", "Pinch again to confirm")
    val right get() = t("✓ 对了", "✓ Right")
    val selfCrossed get() = t("这个手型系统认不准，对照着自己判断", "This shape is hard to read; compare it yourself")
    val selfMotion get() = t("跟着老师的手做一遍动作，自己判断", "Do the move with the teacher's hand and judge it yourself")
    val iMadeIt get() = t("我摆对了", "I've got it")
    fun quizTitle(i: Int, n: Int) = t("小测 $i / $n", "Quiz $i / $n")
    fun reviewTitle(i: Int, n: Int) = t("复习 $i / $n", "Review $i / $n")
    fun retryTitle(i: Int, n: Int) = t("再练 $i / $n", "Again $i / $n")
    val quizLine get() = t("摆出这个字母", "Make this letter")
    val ready get() = t("我摆好了", "I'm ready")
    val dontKnow get() = t("看答案", "Show me")
    val nextQuestion get() = t("下一题", "Next")
    val almost get() = t("✗ 差一点", "✗ Almost")
    fun almostPart(part: String) = if (en) "✗ Almost: $part" else "✗ 差一点：$part"
    val likeThis get() = t("这样摆", "Like this")
    val compare get() = t("和老师的手比一比", "Compare with the teacher's hand")
    val yes get() = t("对了", "Right")
    val no get() = t("不对", "Not quite")
    fun lessonDone(n: Int) = t("第 $n 课学完了", "Lesson $n done")
    fun lessonSeen(n: Int) = t("第 $n 课看完了", "Lesson $n watched")
    val allDone get() = t("5 课都学完了", "All 5 lessons done")
    val wordsDone get() = t("拼词完成", "Words done")
    fun firstTry(k: Int, n: Int) = t("$k / $n 一次对", "$k / $n right first time")
    val comeBack get() = t("这些字母明天会回来复习", "These letters come back for review tomorrow")
    val turnOnTracking get() = t("打开手势追踪后可以跟着练", "Turn on hand tracking to practise along")
    val skipped get() = t("跳过了", "skipped")
    val nextLesson get() = t("下一课", "Next lesson")
    val reviewDone get() = t("复习完了", "Review done")
    fun rightCount(k: Int, n: Int) = t("$k / $n 对", "$k / $n right")
    fun tomorrow(n: Int) = t("明天还有 $n 个", "$n more tomorrow")
    val language get() = t("语言", "Language")
    val followSystem get() = t("跟随系统", "System")
    val dominantHand get() = t("惯用手", "Hand")
    val teacherHand get() = t("老师的手", "Teacher's hand")
    val onHand get() = t("叠在手上", "On my hand")
    val besideHand get() = t("放在手旁", "Beside it")
    val clearProgress get() = t("清空进度", "Clear progress")
    val clearAsk get() = t("清空所有学习进度和复习记录？设置会保留", "Clear all progress and reviews? Settings stay")
    val cancel get() = t("取消", "Cancel")
    val clear get() = t("清空", "Clear")
    val cleared get() = t("已清空", "Cleared")
    val about get() = t("关于", "About")
    val aboutBody get() =
        t(
            "这是美国手语 ASL 的手指字母，不是中国手语。\n手模：WebXR Input Profiles generic-hand © 2019 Amazon · MIT",
            "These are ASL fingerspelling letters.\nHand model: WebXR Input Profiles generic-hand © 2019 Amazon · MIT",
        )

    fun version(v: String) = t("版本 $v", "Version $v")

    /** The hand-label line for a hint key (≤ 12 Chinese characters / 30 Latin). */
    fun hint(key: String?): String? {
        key ?: return null
        val p = key.split(':')
        return when (p[0]) {
            "finger" -> {
                val f = finger(p[1])
                if (p[2] == "bend") t("${f}弯下去", "Bend your ${f.lowercase()}") else t("${f}再伸直一点", "Straighten your ${f.lowercase()}")
            }
            "spread" -> if (p[1] == "apart") t("手指分开一点", "Spread your fingers") else t("手指并拢", "Keep fingers together")
            "thumb" ->
                when (p[1]) {
                    "side" -> t("拇指贴到食指旁边", "Thumb along your index")
                    "out" -> t("拇指张开", "Thumb out")
                    "touch" -> t("拇指碰到指尖", "Thumb to the fingertip")
                    "C" -> t("拇指和四指成弧", "Thumb curves like a C")
                    "par" -> t("拇指和食指平行", "Thumb parallel to index")
                    "closed" -> t("拇指收回来", "Fold your thumb in")
                    else -> t("拇指放到老师那里", "Thumb where the teacher's is")
                }
            "palm" ->
                when (p[1]) {
                    "front" -> t("手掌转向前面", "Turn your palm forward")
                    "side" -> t("手掌转向侧面", "Turn your palm sideways")
                    "selfLeft" -> t("手掌转向自己", "Turn your palm to you")
                    else -> t("手掌转向下面", "Turn your palm down")
                }
            "point" -> t("食指横着指向一边", "Point your index sideways")
            else -> null
        }
    }

    /** S10b: "Almost: index". */
    fun missPart(key: String?): String? {
        key ?: return null
        val p = key.split(':')
        return when (p[0]) {
            "finger" -> finger(p[1])
            "thumb" -> t("拇指", "thumb")
            "palm", "point" -> t("手掌朝向", "palm direction")
            "spread" -> t("手指张开", "finger spread")
            else -> null
        }
    }

    private fun finger(id: String) =
        when (id) {
            "thumb" -> t("拇指", "Thumb")
            "index" -> t("食指", "Index finger")
            "middle" -> t("中指", "Middle finger")
            "ring" -> t("无名指", "Ring finger")
            else -> t("小指", "Pinky")
        }
}
