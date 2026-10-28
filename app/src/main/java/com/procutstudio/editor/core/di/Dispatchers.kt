package com.procutstudio.editor.core.di

import javax.inject.Qualifier

/** Dispatcher for blocking IO: file copies, metadata sniffing, MediaCodec work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU heavy but non-blocking work such as bitmap rendering. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Main dispatcher, injected so tests can replace it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
