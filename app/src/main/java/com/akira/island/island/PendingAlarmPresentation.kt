package com.akira.island.island

/** A scheduled alarm may occupy the main card only after an explicit selection. */
internal fun primaryWithPendingAlarm(acts: List<Act>, expanded: Boolean, focus: String?): Act =
    acts.firstOrNull { it !is Act.AlarmA || expanded && focus == it.id } ?: Act.Idle

internal fun secondaryWithPendingAlarm(acts: List<Act>, primary: Act): Act? =
    acts.firstOrNull { !it.transient && it.id != primary.id }

/** The next activity after the main one and the bubble: a third pill, on the bubble's opposite side. */
internal fun thirdActivity(acts: List<Act>, primary: Act, secondary: Act?): Act? =
    if (secondary == null) null else acts.firstOrNull { !it.transient && it.id != primary.id && it.id != secondary.id }
