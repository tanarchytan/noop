package com.noop.data

import androidx.room.Entity

/*
 * Coach transcript storage, added as part of the v101 -> v102 additive migration. Schema only:
 * cancelling an in-flight turn and streaming land on this table later.
 *
 * Persisting a transcript changes nothing about what leaves the device — the coach still sends only a
 * summary of the user's own metrics plus their question, and only after explicit consent.
 */

/**
 * One turn in a coach conversation, oldest first. Natural key (conversationId, seq), so a replay
 * writes the same row.
 *
 * There is no separate conversation header: a conversation's start and title read off its own rows,
 * and a second table holding either would be a second source of truth. The system prompt is supplied
 * by [com.noop.ai.AiCoach] at call time and is never stored here.
 */
@Entity(tableName = "coachMessage", primaryKeys = ["conversationId", "seq"])
data class CoachMessageRow(
    /** Opaque local id grouping the turns of one conversation. */
    val conversationId: String,
    /** Position within the conversation, from 0. */
    val seq: Int,
    /** `user` or `assistant`. */
    val role: String,
    val text: String,
    /** Wall-clock unix seconds. */
    val createdAt: Long,
    /** Which provider and model answered. Null on a `user` turn; a reply is only comparable to another
     *  when the model behind it is known. */
    val provider: String? = null,
    val model: String? = null,
    /** True when the user's own metric summary was attached to this turn. Recorded, not inferred. */
    val contextIncluded: Boolean = false,
    /** The failure text when a turn did not complete. A failed turn is kept, so the transcript shows
     *  that nothing came back rather than leaving a gap. */
    val error: String? = null,
)
