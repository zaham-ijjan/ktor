/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests

import io.ktor.http.*
import io.ktor.http.CacheControl.*
import io.ktor.server.plugins.cachingheaders.*
import kotlin.test.*

class CacheControlMergeTest {
    @Test
    fun testMergeEmpty() {
        assertEquals(emptyList(), merge())
        assertEquals(emptyList(), merge(listOf(getMustUnderstandDirective), listOf()))
    }

    @Test
    fun testMergeSingleEntry() {
        assertEquals(listOf(NoStore(null)), merge(NoStore(null)))
        assertEquals(listOf(NoStore(Visibility.Public)), merge(NoStore(Visibility.Public)))
        assertEquals(listOf(NoStore(Visibility.Private)), merge(NoStore(Visibility.Private)))
        assertEquals(listOf(NoCache(null)), merge(NoCache(null)))
        assertEquals(listOf(NoCache(Visibility.Public)), merge(NoCache(Visibility.Public)))
        assertEquals(listOf(NoCache(Visibility.Private)), merge(NoCache(Visibility.Private)))
        assertEquals(listOf(MaxAge(1)), merge(MaxAge(1)))
        assertEquals(listOf(MaxAge(2)), merge(MaxAge(2)))
        assertEquals(listOf(Immutable(Visibility.Public)), merge(Immutable(Visibility.Public)))
        assertEquals(listOf(Immutable(Visibility.Private)), merge(Immutable(Visibility.Private)))
        assertEquals(listOf(Immutable(null)), merge(Immutable(null)))
        assertEquals(
            listOf(MaxAge(3, visibility = Visibility.Private)),
            merge(MaxAge(3, visibility = Visibility.Private))
        )
        assertEquals(
            listOf(MustUnderstand(null)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(null)))
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Public)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(Visibility.Public)))
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Private)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(Visibility.Private)))
        )
    }

    @Test
    fun testOrderNoMaxAge() {
        assertEquals(
            listOf(NoCache(null), NoStore(null), Immutable(null)),
            merge(NoCache(null), NoStore(null), Immutable(null))
        )
        assertEquals(
            listOf(NoCache(null), NoStore(null), Immutable(null)),
            merge(Immutable(null), NoStore(null), NoCache(null))
        )
        assertEquals(
            listOf(NoCache(null), NoStore(null), Immutable(null), MustUnderstand(null)),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(NoCache(null), NoStore(null), Immutable(null), MustUnderstand(null))
            )
        )
        assertEquals(
            listOf(NoCache(null), NoStore(null), Immutable(null), MustUnderstand(null)),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(MustUnderstand(null), Immutable(null), NoStore(null), NoCache(null))
            )
        )
    }

    @Test
    fun testMergeNoMaxAge() {
        assertEquals(
            listOf(NoCache(null), NoStore(null), Immutable(null)),
            merge(NoCache(null), NoStore(null), Immutable(null))
        )
        assertEquals(listOf(NoCache(null)), merge(NoCache(null), NoCache(null)))
        assertEquals(listOf(NoCache(Visibility.Private)), merge(NoCache(null), NoCache(Visibility.Private)))
        assertEquals(listOf(NoCache(Visibility.Private)), merge(NoCache(Visibility.Private), NoCache(null)))
        assertEquals(
            listOf(MustUnderstand(null)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(null), MustUnderstand(null)))
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Private)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(null), MustUnderstand(Visibility.Private)))
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Private)),
            merge(listOf(getMustUnderstandDirective), listOf(MustUnderstand(Visibility.Private), MustUnderstand(null)))
        )
    }

    @Test
    fun testTripleMergeNoMaxAge() {
        assertEquals(
            listOf(NoCache(Visibility.Private)),
            merge(
                NoCache(Visibility.Private),
                NoCache(null),
                NoCache(Visibility.Public),
            )
        )
        assertEquals(
            listOf(NoCache(Visibility.Public)),
            merge(
                NoCache(Visibility.Public),
                NoCache(null),
                NoCache(Visibility.Public),
            )
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Private)),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MustUnderstand(Visibility.Private),
                    MustUnderstand(null),
                    MustUnderstand(Visibility.Public)
                )
            )
        )
        assertEquals(
            listOf(MustUnderstand(Visibility.Public)),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MustUnderstand(Visibility.Public),
                    MustUnderstand(null),
                    MustUnderstand(Visibility.Public)
                )
            )
        )
    }

    @Test
    fun testPrivateMergeNoMaxAge() {
        assertEquals(
            listOf(NoCache(Visibility.Private), NoStore(null)),
            merge(NoCache(Visibility.Private), NoStore(null))
        )
        assertEquals(
            listOf(NoCache(Visibility.Private), NoStore(null)),
            merge(NoCache(Visibility.Private), NoStore(Visibility.Private))
        )
        assertEquals(
            listOf(NoCache(Visibility.Private), NoStore(null)),
            merge(NoCache(Visibility.Private), NoStore(Visibility.Public))
        )
        assertEquals(
            listOf(NoCache(Visibility.Private), NoStore(null)),
            merge(NoCache(Visibility.Public), NoStore(Visibility.Private))
        )
    }

    @Test
    fun testPublicMergeNoMaxAge() {
        assertEquals(
            listOf(NoCache(Visibility.Public), NoStore(null)),
            merge(NoCache(Visibility.Public), NoStore(null))
        )
        assertEquals(
            listOf(NoCache(Visibility.Public), NoStore(null)),
            merge(NoCache(null), NoStore(Visibility.Public))
        )
        assertEquals(
            listOf(NoCache(Visibility.Public), NoStore(null)),
            merge(NoCache(Visibility.Public), NoStore(Visibility.Public))
        )
    }

    @Test
    fun testSimpleMaxAgeMerge() {
        assertEquals(
            listOf(MaxAge(1, 1, true, true, Visibility.Private)),
            merge(
                MaxAge(1, 2, true, false, null),
                MaxAge(2, 1, false, true, Visibility.Public),
                MaxAge(20, 10, false, false, Visibility.Private)
            )
        )
    }

    @Test
    fun testAgeMergeWithNoCache() {
        assertEquals(
            listOf(
                NoCache(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, null),
                NoCache(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                NoCache(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                MaxAge(1, 2, true, false, Visibility.Public)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                NoCache(null)
            )
        )
    }

    @Test
    fun testAgeMergeWithNoStore() {
        assertEquals(
            listOf(
                NoStore(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, null),
                NoStore(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                NoStore(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                NoStore(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                NoStore(null),
                MaxAge(1, 2, true, false, Visibility.Public)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                NoStore(null)
            )
        )
    }

    @Test
    fun testAgeMergeWithImmutable() {
        assertEquals(
            listOf(
                Immutable(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, null),
                Immutable(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                Immutable(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                Immutable(Visibility.Private)
            )
        )

        assertEquals(
            listOf(
                Immutable(null),
                MaxAge(1, 2, true, false, Visibility.Public)
            ),
            merge(
                MaxAge(1, 2, true, false, Visibility.Public),
                Immutable(null)
            )
        )
    }

    @Test
    fun testAgeMergeWithMustUnderstand() {
        assertEquals(
            listOf(
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, null),
                    MustUnderstand(Visibility.Private)
                )
            )
        )

        assertEquals(
            listOf(
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, Visibility.Public),
                    MustUnderstand(Visibility.Private)
                )
            )
        )

        assertEquals(
            listOf(
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Public)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, Visibility.Public),
                    MustUnderstand(null)
                )
            )
        )
    }

    @Test
    fun testAgeMergeWithAllDirectives() {
        assertEquals(
            listOf(
                NoCache(null),
                NoStore(null),
                Immutable(null),
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, null),
                    NoStore(Visibility.Private),
                    NoCache(Visibility.Private),
                    Immutable(Visibility.Private),
                    MustUnderstand(Visibility.Private)
                )
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                NoStore(null),
                Immutable(null),
                MustUnderstand(null),
                MaxAge(1, 2, true, false, null)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, null),
                    NoStore(null),
                    NoCache(null),
                    Immutable(null),
                    MustUnderstand(null)
                )
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                NoStore(null),
                Immutable(null),
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Public)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, null),
                    NoStore(Visibility.Public),
                    NoCache(Visibility.Public),
                    Immutable(Visibility.Public),
                    MustUnderstand(Visibility.Public),
                )
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                NoStore(null),
                Immutable(null),
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, Visibility.Private),
                    NoStore(null),
                    NoCache(null),
                    Immutable(null),
                    MustUnderstand(null),
                )
            )
        )

        assertEquals(
            listOf(
                NoCache(null),
                NoStore(null),
                Immutable(null),
                MustUnderstand(null),
                MaxAge(1, 2, true, false, Visibility.Private)
            ),
            merge(
                listOf(getMustUnderstandDirective),
                listOf(
                    MaxAge(1, 2, true, false, Visibility.Public),
                    NoStore(null),
                    NoCache(null),
                    Immutable(null),
                    NoCache(Visibility.Public),
                    NoStore(Visibility.Private),
                    Immutable(Visibility.Public),
                    MustUnderstand(null),
                    MustUnderstand(Visibility.Private),
                )
            )
        )
    }

    private fun merge(vararg cacheControl: CacheControl): List<CacheControl> {
        return cacheControl.asList().mergeCacheControlDirectives()
    }

    private fun merge(
        customDirectiveProviders: List<(List<CacheControl>) -> CustomCacheControlDirective?>,
        cacheControl: List<CacheControl>
    ): List<CacheControl> {
        return cacheControl.mergeCacheControlDirectives(customDirectiveProviders)
    }

    private val getMustUnderstandDirective: (List<CacheControl>) -> CustomCacheControlDirective? =
        lambda@{ cacheHeaders ->
            val foundDirective = cacheHeaders.firstOrNull { it is MustUnderstand } as MustUnderstand?
            foundDirective?.let {
                return@lambda MustUnderstand(null)
            }
            return@lambda null
        }

    private class MustUnderstand(visibility: Visibility?) : CustomCacheControlDirective(visibility) {
        override fun toString(): String = if (visibility == null) {
            "must-understand"
        } else {
            "must-understand, ${visibility!!.headerValue}"
        }

        override fun equals(other: Any?): Boolean {
            return other is MustUnderstand && other.visibility == visibility
        }

        override fun hashCode(): Int {
            return visibility.hashCode()
        }
    }
}
