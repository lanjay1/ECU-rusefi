package com.ztune.libretune.core.tune

/**
 * P1: TuneCache with ShadowMemory for dirty tracking.
 * Ported from LibreTune's tune/cache.rs.
 *
 * Tracks per-page state (NotLoaded→Loading→Clean→Dirty→Pending→Clean)
 * and per-byte dirty bits for efficient partial writes.
 */
enum class PageState { NOT_LOADED, LOADING, CLEAN, DIRTY, PENDING, ERROR }

class ShadowMemory {
    private val dirtyBytes: MutableMap<Pair<Int, Int>, Boolean> = mutableMapOf()

    fun markDirty(page: Int, offset: Int) { dirtyBytes[page to offset] = true }
    fun clear() { dirtyBytes.clear() }

    fun isDirty(page: Int, offset: Int): Boolean = dirtyBytes[page to offset] == true
    fun hasDirtyData(): Boolean = dirtyBytes.isNotEmpty()
    fun dirtyByteCount(): Int = dirtyBytes.size

    fun dirtyPages(): Set<Int> = dirtyBytes.keys.map { it.first }.toSet()

    fun dirtyRanges(page: Int): List<Pair<Int, Int>> {
        val offsets = dirtyBytes.keys.filter { it.first == page }.map { it.second }.sorted()
        if (offsets.isEmpty()) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = offsets[0]; var end = offsets[0]
        for (i in 1 until offsets.size) {
            if (offsets[i] == end + 1) { end = offsets[i] }
            else { ranges.add(start to end + 1); start = offsets[i]; end = offsets[i] }
        }
        ranges.add(start to end + 1)
        return ranges
    }
}

class TuneCache(
    private val pageSizes: List<Short>,
    private val nPages: Byte
) {
    private val pages: MutableMap<Int, ByteArray> = mutableMapOf()
    private val pageStates: MutableMap<Int, PageState> = mutableMapOf()
    val shadow = ShadowMemory()
    var hasPendingBurn: Boolean = false; private set

    fun isFullyLoaded(): Boolean = (0 until nPages).all { pageStates[it] == PageState.CLEAN || pageStates[it] == PageState.DIRTY }
    fun isLoading(): Boolean = pageStates.values.any { it == PageState.LOADING }
    fun pagesToLoad(): List<Int> = (0 until nPages).filter { pageStates[it] == null || pageStates[it] == PageState.NOT_LOADED }

    fun markLoading(page: Int) { pageStates[page] = PageState.LOADING }
    fun markError(page: Int) { pageStates[page] = PageState.ERROR }
    fun loadPage(page: Int, data: ByteArray) { pages[page] = data; pageStates[page] = PageState.CLEAN }

    fun readBytes(page: Int, offset: Int, length: Int): ByteArray? {
        val pageData = pages[page] ?: return null
        if (offset + length > pageData.size) return null
        return pageData.copyOfRange(offset, offset + length)
    }

    fun writeBytes(page: Int, offset: Int, data: ByteArray): Boolean {
        val pageData = pages[page]?.let { it.copyOf() } ?: return false
        if (offset + data.size > pageData.size) return false
        System.arraycopy(data, 0, pageData, offset, data.size)
        pages[page] = pageData
        pageStates[page] = PageState.DIRTY
        for (i in data.indices) shadow.markDirty(page, offset + i)
        return true
    }

    fun hasDirtyData(): Boolean = shadow.hasDirtyData()
    fun dirtyPages(): Set<Int> = shadow.dirtyPages()
    fun dirtyByteCount(): Int = shadow.dirtyByteCount()

    fun markPending() { shadow.dirtyPages().forEach { pageStates[it] = PageState.PENDING }; hasPendingBurn = true }
    fun markBurned() {
        pageStates.forEach { (p, _) -> if (pageStates[p] == PageState.PENDING) pageStates[p] = PageState.CLEAN }
        shadow.clear(); hasPendingBurn = false
    }
    fun revert() {
        pageStates.forEach { (p, s) -> if (s == PageState.DIRTY) pageStates[p] = PageState.CLEAN }
        shadow.clear(); hasPendingBurn = false
    }
}
