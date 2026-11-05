package io.github.taehunkim.jogakbo.ui.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Navigation after suspending work must happen on the main thread (it is not guaranteed after every resume). */
suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
