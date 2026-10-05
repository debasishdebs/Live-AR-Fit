package com.debasish.livefit.services.music

import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.services.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Demo playlist standing in for the YouTube Music media session. */
class FakeMusicService(scope: CoroutineScope) : MusicService {
    private val playlist = listOf(
        NowPlaying("Waka Waka (Esto es Africa)", "Shakira", isPlaying = true, durationMs = 202_000),
        NowPlaying("Mamacita Buena", "Claydee", isPlaying = true, durationMs = 188_000),
        NowPlaying("Eye of the Tiger", "Survivor", isPlaying = true, durationMs = 245_000),
        NowPlaying("Stronger", "Kanye West", isPlaying = true, durationMs = 311_000),
    )
    private var index = 0

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(playlist[0])
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _volume = MutableStateFlow(0.6f)
    override val volume: StateFlow<Float> = _volume

    init {
        scope.launch {
            while (true) {
                delay(1_000)
                val np = _nowPlaying.value ?: continue
                if (!np.isPlaying) continue
                if (np.positionMs + 1_000 >= np.durationMs) next() else _nowPlaying.value = np.copy(positionMs = np.positionMs + 1_000)
            }
        }
    }

    override fun playPause() = _nowPlaying.update { it?.copy(isPlaying = !it.isPlaying) }
    override fun play() = _nowPlaying.update { it?.copy(isPlaying = true) }
    override fun pause() = _nowPlaying.update { it?.copy(isPlaying = false) }
    override fun next() = jump(+1)
    override fun previous() = jump(-1)
    override fun toggleLike() = _nowPlaying.update { it?.copy(liked = !it.liked) }
    override fun setVolume(level: Float) { _volume.value = level.coerceIn(0f, 1f) }

    private fun jump(delta: Int) {
        index = (index + delta + playlist.size) % playlist.size
        _nowPlaying.value = playlist[index]
    }
}
