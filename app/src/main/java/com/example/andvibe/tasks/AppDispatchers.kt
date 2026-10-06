package com.example.andvibe.tasks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    /** One lane for repo-mutating work — preserves the old single-thread io serialization. */
    val repo: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /** The agent's own lane — preserves the old dedicated agent executor. */
    val agent: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    val io: CoroutineDispatcher = Dispatchers.IO,
)
