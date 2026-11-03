package io.embodit.signhands.domain

/**
 * v0.1.3 (user on the headset: "需要更多词的，现在就 HI 和 No…把常用的日常英语的词都加了"; profile B18): everyday ASL
 * word signs grouped by theme. Each sign is a handshape the hand model already has (a letter pose or OPEN), a palm
 * orientation, where the palm centre starts (head frame, metres, right hand: +X dominant side, +Y up, -Z ahead) and
 * the path it moves along. Two-handed signs also carry the other hand's resting shape (shown in the demo, not judged).
 *
 * Source: the word list is the everyday vocabulary of beginner ASL courses (greetings, politeness, yes / no, family,
 * food, time, feelings, verbs, questions); the movement descriptions are written for this app from general ASL
 * knowledge and simplified to one hand path. No text, picture or video was copied from a dictionary. See AGENTS.md.
 */
enum class Theme(val zh: String, val en: String) {
    GREET("问候", "Greetings"),
    POLITE("礼貌", "Polite words"),
    YESNO("是 / 否", "Yes & no"),
    FAMILY("家人", "Family"),
    FOOD("吃喝", "Food & drink"),
    TIME("时间", "Time"),
    FEEL("情绪", "Feelings"),
    VERB("常用动词", "Verbs"),
    QUESTION("问句", "Questions"),
}

/** The non-dominant hand of a two-handed sign: a resting shape at a spot (head frame, right-hand terms, mirrored). */
data class BaseHand(val shape: String, val orient: Q4, val at: V3)

data class Sign(
    val id: String,
    val zh: String,
    val theme: Theme,
    /** Letter key of the hand pose ("B", "OPEN", ...). */
    val shape: String,
    /** Hand frame -> head frame (right hand). */
    val orient: Q4,
    /** Palm centre at the start. */
    val at: V3,
    /** Offsets of the palm centre from [at]; the first is zero. */
    val path: List<V3>,
    val tipZh: String,
    val tipEn: String,
    val base: BaseHand? = null,
) {
    val en: String
        get() = id.replace('_', ' ')

    fun tip(lang: Lang): String = if (lang == Lang.EN) tipEn else tipZh

    fun title(en: Boolean): String = if (en) this.en else "$zh ${this.en}"

    /** The progress key of a followed sign. */
    val key: String
        get() = "w:$id"

    /** Palm centre at path fraction [t] (0..1, piecewise linear between the keys, eased per segment). */
    fun palmAt(t: Float): V3 {
        if (path.size < 2) return at
        val u = t.coerceIn(0f, 1f) * (path.size - 1)
        val i = u.toInt().coerceAtMost(path.size - 2)
        val k = smoothstep(u - i)
        return at + path[i] + (path[i + 1] - path[i]) * k
    }
}

/** Palm orientations (right hand, head frame) and body spots used by the catalogue. */
object Pose {
    private fun basis(x: V3, y: V3, z: V3) = Q4.fromBasis(x, y, z)

    /** Palm facing forward, fingers up (the letters' "front"). */
    val FWD = Q4.IDENTITY
    /** Palm towards you, fingers up. */
    val IN = basis(V3(-1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, -1f))
    /** Palm down, fingers forward. */
    val DOWN = basis(V3(1f, 0f, 0f), V3(0f, 0f, -1f), V3(0f, 1f, 0f))
    /** Palm up, fingers forward. */
    val UP = basis(V3(-1f, 0f, 0f), V3(0f, 0f, -1f), V3(0f, -1f, 0f))
    /** Palm towards the other side, fingers up (thumb towards you). */
    val SIDE = basis(V3(0f, 0f, -1f), V3(0f, 1f, 0f), V3(1f, 0f, 0f))
    /** Palm towards the other side, fingers forward (thumb up). */
    val SIDE_FWD = basis(V3(0f, -1f, 0f), V3(0f, 0f, -1f), V3(1f, 0f, 0f))
    /** Palm towards you, fingers to the other side (thumb up): a hand flat on the chest. */
    val CHEST = basis(V3(0f, -1f, 0f), V3(-1f, 0f, 0f), V3(0f, 0f, -1f))
    /** Palm down, fingers to the other side. */
    val DOWN_ACROSS = basis(V3(0f, 0f, -1f), V3(-1f, 0f, 0f), V3(0f, 1f, 0f))
    /** Fingertips towards your mouth, palm down (a flat O feeding). */
    val TO_MOUTH = basis(V3(-1f, 0f, 0f), V3(0f, 0f, 1f), V3(0f, 1f, 0f))

    val FOREHEAD = V3(0.05f, 0.07f, -0.13f)
    val EYES = V3(0.04f, 0.0f, -0.15f)
    val CHEEK = V3(0.09f, -0.06f, -0.12f)
    val MOUTH = V3(0.03f, -0.08f, -0.14f)
    val CHIN = V3(0.03f, -0.11f, -0.13f)
    val CHEST_AT = V3(0.02f, -0.32f, -0.20f)
    /** Signing space in front of you, a little to the dominant side (near the teaching spot). */
    val SPACE = V3(0.08f, -0.26f, -0.34f)

    /** Where the other hand rests for two-handed signs. */
    val BASE_SPOT = V3(-0.04f, -0.30f, -0.32f)
}

object Signs {
    private fun v(x: Float, y: Float, z: Float) = V3(x, y, z)

    private val Z = V3.ZERO

    private fun tap(dx: Float, dy: Float, dz: Float) = listOf(Z, v(dx, dy, dz), Z)

    private fun circle(r: Float, plane: String = "xy", n: Int = 8): List<V3> =
        (0..n).map { i ->
            val a = (i / n.toFloat()) * 2f * kotlin.math.PI.toFloat()
            val c = kotlin.math.cos(a) - 1f
            val s = kotlin.math.sin(a)
            if (plane == "xz") v(c * r, 0f, s * r) else v(c * r, s * r, 0f)
        }

    private fun shake(dx: Float) = listOf(Z, v(dx, 0f, 0f), v(-dx, 0f, 0f), Z)

    private fun base(shape: String, orient: Q4, at: V3 = Pose.BASE_SPOT) = BaseHand(shape, orient, at)

    val all: List<Sign> =
        listOf(
            // ---------------------------------------------------------------- greetings
            Sign("HELLO", "你好", Theme.GREET, "B", Pose.FWD, Pose.FOREHEAD + v(0.03f, 0f, 0f), listOf(Z, v(0.11f, 0.01f, -0.07f)),
                "平伸的手从额头旁向外挥出，像敬礼", "Flat hand from your forehead out to the side, like a salute"),
            Sign("BYE", "再见", Theme.GREET, "OPEN", Pose.FWD, Pose.SPACE + v(0f, 0.16f, 0f), shake(0.05f),
                "手掌朝前举起，左右挥一挥", "Palm forward, wave side to side"),
            Sign("NICE", "很好 / 干净", Theme.GREET, "B", Pose.DOWN, Pose.SPACE + v(-0.06f, -0.02f, 0.04f), listOf(Z, v(0f, 0f, -0.12f)),
                "手掌朝下，从另一只手的掌心向前滑过", "Palm down, slide forward across your other palm", base("B", Pose.UP)),
            Sign("MEET", "见面", Theme.GREET, "D", Pose.SIDE, Pose.SPACE + v(0.06f, 0.04f, 0f), listOf(Z, v(-0.11f, 0f, 0f)),
                "两手食指竖起，相向靠拢碰在一起", "Both index fingers up, bring them together", base("D", Pose.SIDE, Pose.BASE_SPOT + v(-0.02f, 0.06f, 0f))),
            Sign("FINE", "挺好", Theme.GREET, "OPEN", Pose.CHEST, Pose.CHEST_AT + v(0.05f, 0.04f, -0.04f), tap(0f, 0f, -0.05f),
                "五指张开，拇指在胸口点一点", "Open hand, tap your thumb on your chest"),
            Sign("NAME", "名字", Theme.GREET, "H", Pose.SIDE_FWD, Pose.SPACE + v(-0.02f, 0.05f, 0f), tap(0f, -0.04f, 0f),
                "两手比 H，上面的两指在下面的两指上敲两下", "Both hands in H, tap the top fingers on the bottom ones twice", base("H", Pose.SIDE_FWD)),
            // ---------------------------------------------------------------- polite words
            Sign("PLEASE", "请", Theme.POLITE, "B", Pose.CHEST, Pose.CHEST_AT, circle(0.045f),
                "手掌贴在胸前画圈", "Flat hand on your chest, rub in a circle"),
            Sign("THANK_YOU", "谢谢", Theme.POLITE, "B", Pose.IN, Pose.CHIN + v(0.02f, -0.02f, 0f), listOf(Z, v(0.03f, -0.07f, -0.15f)),
                "指尖从下巴向前向下送出", "Fingertips from your chin, move forward and down"),
            Sign("SORRY", "对不起", Theme.POLITE, "A", Pose.CHEST, Pose.CHEST_AT, circle(0.04f),
                "握拳贴在胸前画圈", "Fist on your chest, rub in a circle"),
            Sign("EXCUSE_ME", "打扰一下", Theme.POLITE, "B", Pose.DOWN_ACROSS, Pose.SPACE + v(-0.04f, -0.01f, 0.03f), listOf(Z, v(0.02f, 0f, -0.09f), v(0f, 0.02f, 0f)),
                "指尖在另一只手的掌心向前刷两下", "Brush your fingertips forward across your other palm", base("B", Pose.UP)),
            Sign("WELCOME", "欢迎 / 不客气", Theme.POLITE, "B", Pose.UP, Pose.SPACE + v(0.08f, 0.02f, -0.06f), listOf(Z, v(-0.07f, 0.01f, 0.14f)),
                "手掌朝上，从外向身前划进来", "Palm up, sweep in towards your body"),
            // ---------------------------------------------------------------- yes / no
            Sign("YES", "是", Theme.YESNO, "S", Pose.FWD, Pose.SPACE + v(0f, 0.08f, 0f), tap(0f, -0.045f, -0.015f),
                "握拳像点头一样上下点", "Fist nods up and down like a head"),
            Sign("NO", "不", Theme.YESNO, "U", Pose.SIDE_FWD, Pose.SPACE + v(0f, 0.08f, 0f), tap(0f, -0.035f, 0.01f),
                "食指中指并拢，和拇指捏合两下", "Index and middle fingers snap down onto your thumb"),
            Sign("NOT", "不是", Theme.YESNO, "A", Pose.SIDE, Pose.CHIN + v(0.02f, -0.03f, 0f), listOf(Z, v(0.02f, -0.02f, -0.13f)),
                "拇指抵在下巴下，向前弹出", "Thumb under your chin, flick it forward"),
            Sign("MAYBE", "也许", Theme.YESNO, "B", Pose.UP, Pose.SPACE, listOf(Z, v(0f, 0.06f, 0f), v(0f, -0.03f, 0f)),
                "两手掌心朝上，像天平一上一下", "Both palms up, move up and down like scales", base("B", Pose.UP)),
            Sign("RIGHT", "对", Theme.YESNO, "D", Pose.SIDE_FWD, Pose.SPACE + v(-0.02f, 0.07f, 0f), listOf(Z, v(0f, -0.07f, 0f)),
                "食指指向前，落在另一只手的食指上", "Index pointing forward, drop it onto your other index", base("D", Pose.SIDE_FWD)),
            // ---------------------------------------------------------------- family
            Sign("MOTHER", "妈妈", Theme.FAMILY, "OPEN", Pose.SIDE, Pose.CHIN + v(0.05f, 0.04f, 0f), tap(0f, 0f, -0.035f),
                "五指张开，拇指在下巴点两下", "Open hand, tap your thumb on your chin"),
            Sign("FATHER", "爸爸", Theme.FAMILY, "OPEN", Pose.SIDE, Pose.FOREHEAD + v(0.03f, 0.03f, 0f), tap(0f, 0f, -0.035f),
                "五指张开，拇指在额头点两下", "Open hand, tap your thumb on your forehead"),
            Sign("SISTER", "姐妹", Theme.FAMILY, "L", Pose.SIDE_FWD, Pose.CHEEK + v(-0.02f, -0.02f, -0.02f), listOf(Z, v(-0.07f, -0.19f, -0.12f)),
                "L 手从脸颊往下，落在另一只 L 手上", "L hand from your jaw down onto your other L hand", base("L", Pose.SIDE_FWD)),
            Sign("BROTHER", "兄弟", Theme.FAMILY, "L", Pose.SIDE_FWD, Pose.FOREHEAD, listOf(Z, v(-0.09f, -0.33f, -0.13f)),
                "L 手从额头往下，落在另一只 L 手上", "L hand from your forehead down onto your other L hand", base("L", Pose.SIDE_FWD)),
            Sign("BABY", "宝宝", Theme.FAMILY, "B", Pose.UP, Pose.SPACE + v(-0.02f, -0.02f, 0.04f), listOf(Z, v(-0.08f, 0.03f, 0f), Z),
                "两臂像抱着宝宝一样左右摇", "Cradle your arms and rock side to side", base("B", Pose.UP)),
            Sign("FAMILY", "家庭", Theme.FAMILY, "F", Pose.FWD, Pose.SPACE + v(-0.03f, 0.06f, 0f), listOf(Z, v(0.06f, 0f, 0.03f), v(0.07f, 0f, 0.09f), v(0.03f, 0f, 0.13f)),
                "两手比 F，从前面向两边画半圈，到身前合拢", "Both hands in F, circle outwards until the pinkies meet", base("F", Pose.FWD, Pose.BASE_SPOT + v(0f, 0.06f, 0f))),
            Sign("FRIEND", "朋友", Theme.FAMILY, "X", Pose.DOWN_ACROSS, Pose.SPACE + v(-0.02f, 0.06f, 0f), listOf(Z, v(0f, -0.05f, 0f), v(0.02f, -0.02f, 0.04f)),
                "两只钩起的食指勾在一起，再翻过来勾一次", "Hook your index fingers together, then flip and hook again", base("X", Pose.UP)),
            // ---------------------------------------------------------------- food & drink
            Sign("EAT", "吃", Theme.FOOD, "O", Pose.TO_MOUTH, Pose.MOUTH + v(0f, -0.01f, -0.07f), tap(0f, 0f, 0.05f),
                "指尖捏在一起，往嘴边送两下", "Fingertips together, bring them to your mouth twice"),
            Sign("DRINK", "喝", Theme.FOOD, "C", Pose.SIDE, Pose.MOUTH + v(0.01f, -0.06f, -0.08f), listOf(Z, v(0f, 0.04f, 0.05f)),
                "手比 C 像拿着杯子，往嘴边一倾", "C hand like holding a cup, tip it to your mouth"),
            Sign("WATER", "水", Theme.FOOD, "W", Pose.SIDE, Pose.CHIN + v(0.04f, 0.01f, -0.03f), tap(0f, 0f, -0.035f),
                "手比 W，食指在下巴点两下", "W hand, tap your index on your chin twice"),
            Sign("MILK", "牛奶", Theme.FOOD, "S", Pose.SIDE, Pose.SPACE + v(0f, 0.04f, 0f), tap(0f, -0.04f, 0f),
                "像挤牛奶一样握拳、松开", "Squeeze your fist open and closed, like milking"),
            Sign("MORE", "更多", Theme.FOOD, "O", Pose.SIDE_FWD, Pose.SPACE + v(0.04f, 0.02f, 0f), tap(-0.06f, 0f, 0f),
                "两手指尖捏拢，指尖互相碰两下", "Both fingertips bunched, tap them together twice", base("O", Pose.SIDE_FWD, Pose.BASE_SPOT + v(0.02f, 0.04f, 0f))),
            Sign("HUNGRY", "饿", Theme.FOOD, "C", Pose.IN, Pose.CHEST_AT + v(0f, 0.12f, 0f), listOf(Z, v(0f, -0.16f, 0f)),
                "手比 C 朝着自己，从胸口往下移", "C hand facing you, move down your chest"),
            Sign("FINISHED", "完了", Theme.FOOD, "OPEN", Pose.IN, Pose.SPACE + v(0f, 0.06f, 0.06f), listOf(Z, v(0.07f, -0.02f, -0.05f)),
                "两手五指张开朝着自己，向外一甩", "Open hands facing you, flick them outwards", base("OPEN", Pose.IN, Pose.BASE_SPOT + v(0f, 0.06f, 0.04f))),
            // ---------------------------------------------------------------- time
            Sign("NOW", "现在", Theme.TIME, "Y", Pose.UP, Pose.SPACE + v(0f, 0.05f, 0.02f), listOf(Z, v(0f, -0.07f, 0f)),
                "两手比 Y 掌心朝上，一起往下落", "Both hands in Y, palms up, drop them together", base("Y", Pose.UP, Pose.BASE_SPOT + v(0f, 0.05f, 0f))),
            Sign("TOMORROW", "明天", Theme.TIME, "A", Pose.SIDE, Pose.CHEEK + v(0.02f, 0f, 0.02f), listOf(Z, v(0.02f, 0f, -0.11f)),
                "拇指贴着脸颊，向前划出", "Thumb on your cheek, move it forward"),
            Sign("YESTERDAY", "昨天", Theme.TIME, "Y", Pose.SIDE, Pose.CHIN + v(0.06f, 0.02f, 0f), listOf(Z, v(0.05f, 0.05f, 0.07f)),
                "手比 Y，拇指从下巴往后移到耳边", "Y hand, thumb from your chin back towards your ear"),
            Sign("LATER", "等会儿", Theme.TIME, "L", Pose.FWD, Pose.SPACE + v(0f, 0.08f, 0f), listOf(Z, v(0.015f, -0.02f, -0.06f)),
                "手比 L，食指向前转下去", "L hand, tip your index forward"),
            Sign("TIME", "时间", Theme.TIME, "X", Pose.DOWN, Pose.SPACE + v(-0.03f, 0.04f, 0f), tap(0f, -0.045f, 0f),
                "钩起的食指在另一只手的手腕上点两下", "Hooked index taps the back of your other wrist", base("S", Pose.DOWN)),
            Sign("MORNING", "早上", Theme.TIME, "B", Pose.IN, Pose.SPACE + v(0f, -0.04f, 0f), listOf(Z, v(0f, 0.13f, 0.03f)),
                "手掌朝自己，像太阳一样从下往上升", "Palm towards you, rise up like the sun", base("B", Pose.DOWN_ACROSS)),
            Sign("NIGHT", "晚上", Theme.TIME, "B", Pose.DOWN, Pose.SPACE + v(0f, 0.08f, 0f), listOf(Z, v(-0.06f, -0.06f, 0f)),
                "弯着的手掌朝下，在另一只手腕上像太阳一样落下", "Bent hand, palm down, set over your other wrist like the sun", base("B", Pose.DOWN_ACROSS)),
            // ---------------------------------------------------------------- feelings
            Sign("HAPPY", "开心", Theme.FEEL, "B", Pose.CHEST, Pose.CHEST_AT + v(0f, -0.03f, 0f), listOf(Z, v(0f, 0.1f, -0.04f)),
                "手掌在胸前往上刷两下", "Flat hand brushes up your chest"),
            Sign("SAD", "难过", Theme.FEEL, "OPEN", Pose.IN, Pose.EYES + v(0.02f, 0f, -0.06f), listOf(Z, v(0f, -0.16f, -0.02f)),
                "两手张开对着脸，慢慢往下移", "Open hands in front of your face, slide them down", base("OPEN", Pose.IN, Pose.EYES + v(0.02f, 0f, -0.06f))),
            Sign("LOVE", "爱", Theme.FEEL, "S", Pose.CHEST, Pose.CHEST_AT + v(-0.04f, 0.03f, -0.04f), listOf(Z, v(0f, 0f, 0.05f)),
                "两手握拳交叉，抱在胸前", "Cross your fists over your chest, like a hug", base("S", Pose.CHEST, Pose.CHEST_AT + v(-0.04f, 0.0f, -0.04f))),
            Sign("LIKE", "喜欢", Theme.FEEL, "OPEN", Pose.CHEST, Pose.CHEST_AT, listOf(Z, v(0.01f, 0.01f, -0.13f)),
                "拇指和中指从胸口向外拉出，边拉边捏拢", "Thumb and middle finger pull out from your chest and pinch"),
            Sign("ANGRY", "生气", Theme.FEEL, "OPEN", Pose.IN, Pose.CHEST_AT + v(0f, -0.04f, 0f), listOf(Z, v(0f, 0.13f, -0.06f)),
                "手指弯成爪，从肚子往上往外拉", "Clawed hand pulls up and out from your stomach"),
            Sign("TIRED", "累", Theme.FEEL, "B", Pose.CHEST, Pose.CHEST_AT + v(0.05f, 0.05f, 0f), listOf(Z, v(0f, -0.05f, -0.03f)),
                "两手指尖搭在胸口，往下一垂", "Fingertips on your chest, let your hands droop", base("B", Pose.CHEST, Pose.CHEST_AT + v(0.01f, 0.05f, 0f))),
            // ---------------------------------------------------------------- verbs
            Sign("GO", "去", Theme.VERB, "D", Pose.FWD, Pose.SPACE + v(0f, 0.04f, 0.06f), listOf(Z, v(0f, 0.03f, -0.08f), v(0f, -0.02f, -0.17f)),
                "食指竖起，向前画个弧指出去", "Index up, arc it forward and point away"),
            Sign("COME", "来", Theme.VERB, "D", Pose.SIDE_FWD, Pose.SPACE + v(0f, 0f, -0.07f), listOf(Z, v(0f, 0.03f, 0.08f), v(0f, 0f, 0.15f)),
                "食指指向前，向自己勾回来", "Index pointing forward, beckon it towards you"),
            Sign("WANT", "想要", Theme.VERB, "OPEN", Pose.UP, Pose.SPACE + v(0f, 0f, -0.05f), listOf(Z, v(0f, 0.01f, 0.13f)),
                "手掌朝上手指弯曲，往身前拉", "Palms up, fingers bent, pull towards you"),
            Sign("NEED", "需要", Theme.VERB, "X", Pose.DOWN, Pose.SPACE + v(0f, 0.05f, 0f), tap(0f, -0.055f, 0f),
                "钩起的食指往下点两下", "Hooked index, dip it down twice"),
            Sign("KNOW", "知道", Theme.VERB, "B", Pose.IN, Pose.FOREHEAD + v(0.04f, -0.02f, 0.02f), tap(0.015f, 0f, -0.035f),
                "指尖在额头旁点一点", "Tap your fingertips on your forehead"),
            Sign("HELP", "帮忙", Theme.VERB, "A", Pose.SIDE, Pose.SPACE + v(-0.03f, -0.01f, 0.01f), listOf(Z, v(0f, 0.11f, 0f)),
                "竖拇指的拳放在另一只手掌上，一起往上抬", "Thumbs-up fist on your other palm, lift both up", base("B", Pose.UP, Pose.BASE_SPOT + v(0f, -0.04f, 0f))),
            Sign("STOP", "停", Theme.VERB, "B", Pose.SIDE, Pose.SPACE + v(-0.03f, 0.11f, 0f), listOf(Z, v(0f, -0.11f, 0f)),
                "手掌立起，像刀一样砍到另一只手掌上", "Flat hand on edge, chop down onto your other palm", base("B", Pose.UP)),
            Sign("LEARN", "学", Theme.VERB, "O", Pose.TO_MOUTH, Pose.SPACE + v(-0.03f, -0.02f, 0f), listOf(Z, v(-0.03f, 0.3f, 0.19f)),
                "从另一只手掌上抓起，放到额头", "Pick it up from your other palm and put it on your forehead", base("B", Pose.UP, Pose.BASE_SPOT + v(0f, -0.04f, 0f))),
            Sign("SEE", "看", Theme.VERB, "V", Pose.IN, Pose.EYES + v(0.02f, -0.05f, -0.05f), listOf(Z, v(0f, -0.01f, -0.14f)),
                "手比 V 从眼睛前向外指出去", "V hand from your eyes, point it forward"),
            Sign("WORK", "工作", Theme.VERB, "S", Pose.DOWN, Pose.SPACE + v(-0.02f, 0.07f, 0f), tap(0f, -0.05f, 0f),
                "拳头在另一只拳头的手背上敲两下", "Tap your fist on the back of your other fist twice", base("S", Pose.DOWN)),
            // ---------------------------------------------------------------- questions
            Sign("WHAT", "什么", Theme.QUESTION, "OPEN", Pose.UP, Pose.SPACE + v(0.02f, 0f, 0f), shake(0.045f),
                "两手掌心朝上，左右轻轻摆", "Both palms up, shake them side to side", base("OPEN", Pose.UP)),
            Sign("WHERE", "哪里", Theme.QUESTION, "D", Pose.FWD, Pose.SPACE + v(0f, 0.12f, 0f), shake(0.045f),
                "食指竖起，左右摆一摆", "Index up, shake it side to side"),
            Sign("WHO", "谁", Theme.QUESTION, "D", Pose.FWD, Pose.MOUTH + v(0.01f, -0.02f, -0.05f), circle(0.03f),
                "食指在嘴前画小圈", "Index in front of your lips, draw small circles"),
            Sign("WHY", "为什么", Theme.QUESTION, "Y", Pose.IN, Pose.FOREHEAD + v(0.03f, -0.01f, 0f), listOf(Z, v(0.04f, -0.07f, -0.11f)),
                "手指碰额头，向外拉出变成 Y", "Touch your forehead, pull out into a Y"),
            Sign("WHEN", "什么时候", Theme.QUESTION, "D", Pose.FWD, Pose.SPACE + v(0f, 0.08f, 0f), circle(0.04f) + listOf(v(-0.02f, -0.05f, 0f)),
                "食指绕一圈，落在另一只食指尖上", "Index circles once, lands on your other index", base("D", Pose.FWD)),
            Sign("HOW", "怎么", Theme.QUESTION, "A", Pose.DOWN, Pose.SPACE + v(-0.02f, 0.02f, 0f), listOf(Z, v(0f, 0.04f, -0.05f)),
                "两手指背相对，向前翻上来", "Backs of your bent fingers together, roll them forward and up", base("A", Pose.DOWN)),
        )

    val byId: Map<String, Sign> = all.associateBy { it.id }

    operator fun get(id: String): Sign? = byId[id.uppercase()]

    fun inTheme(t: Theme): List<Sign> = all.filter { it.theme == t }
}
