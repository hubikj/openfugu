package org.hubik.openfugu.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking file I/O. kotlinx-coroutines does not expose
 * Dispatchers.IO to common code (the JVM and Native each declare their own),
 * hence the expect.
 */
expect val IoDispatcher: CoroutineDispatcher
