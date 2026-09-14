package com.lbc.breadcrumb.ui.debug

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Stand-in saves for steps 1.2 through 1.5, before the share sheet exists.
 *
 * Deliberately shaped like the real thing -- varied types, source apps, and
 * capture dates well in the past -- so the list, and later the search, are
 * exercised against something more honest than "test 1", "test 2".
 *
 * Delete this file once the bulk importer (step 5.1) lands.
 */
private val samples: List<Memory> = listOf(
    Memory(
        type = MemoryType.IMAGE,
        sourceApp = "com.linkedin.android",
        rawText = "Qualcomm SWE internship, applications close April 30",
        localUri = "file:///sample/internship.png",
        extractedText = "Qualcomm | Software Engineering Intern | Bengaluru",
    ),
    Memory(
        type = MemoryType.LINK,
        sourceApp = "com.android.chrome",
        rawText = "https://github.com/square/okhttp",
    ),
    Memory(
        type = MemoryType.TEXT,
        sourceApp = "com.whatsapp",
        rawText = "Naru's in Indiranagar — the omakase, book two weeks ahead",
    ),
    Memory(
        type = MemoryType.PDF,
        sourceApp = "com.google.android.gm",
        rawText = "Distributed Systems — week 6 notes",
        localUri = "file:///sample/ds-week6.pdf",
    ),
    Memory(
        type = MemoryType.TEXT,
        sourceApp = "com.instagram.android",
        rawText = "Kyoto in November for the maples, not spring — half the crowd",
    ),
    Memory(
        type = MemoryType.LINK,
        sourceApp = "com.android.chrome",
        rawText = "https://developer.android.com/develop/ui/compose/performance",
    ),
)

/**
 * A sample with a fresh id and a capture time scattered over the last ~90 days,
 * so ordering and, later, date filtering have something real to sort.
 */
fun randomSampleMemory(): Memory {
    val daysAgo = Random.nextInt(0, 90)
    val capturedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo.toLong())
    return samples.random().copy(
        id = java.util.UUID.randomUUID().toString(),
        capturedAt = capturedAt,
        contentCreatedAt = capturedAt,
        updatedAt = capturedAt,
        syncState = SyncState.PENDING,
    )
}
