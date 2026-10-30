package io.embodit.signhands.platform

/** Process-wide platform services. The scene starts and stops both trackers (every start has a stop). */
object SignGraph {
    val head = HeadTracker()
    val hands = HandTracker()
}
