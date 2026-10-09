package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeChoiceTest {
    private fun n(id: String, near: Boolean) = NodeCandidate(id, "name-$id", near)

    @Test fun emptyStaysEmpty() = assertEquals(emptyList(), chooseNodes(emptyList()))

    @Test fun nearbyComesFirst() =
        assertEquals(listOf("b", "a"), chooseNodes(listOf(n("a", false), n("b", true))).map { it.id })

    @Test fun tiesBreakById() =
        assertEquals(listOf("a", "c", "z"), chooseNodes(listOf(n("z", true), n("a", true), n("c", true))).map { it.id })

    @Test fun nearbyThenFarSortedById() =
        assertEquals(listOf("m", "b", "d"), chooseNodes(listOf(n("d", false), n("m", true), n("b", false))).map { it.id })

    @Test fun duplicatesByIdCollapse() =
        assertEquals(listOf("a"), chooseNodes(listOf(n("a", false), n("a", true))).map { it.id })
}
