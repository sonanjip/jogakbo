package io.github.taehunkim.jogakbo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.audio.AudioDecoder
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.record.FileSource
import io.github.taehunkim.jogakbo.record.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * With OndolSaem's real speech model (skipped without it, or without the fixture `long_talk.wav` from Nunsori's
 * make-audio-fixtures.sh copied into the app's cache: `adb push long_talk.wav /data/local/tmp/` then
 * `adb shell run-as io.github.taehunkim.jogakbo cp /data/local/tmp/long_talk.wav cache/`): the live transcript of a
 * recording fed from a file arrives while it records.
 */
@RunWith(AndroidJUnit4::class)
class RealModelTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Copied into the app's cache before the run (see docs/development.md). */
    private fun fixture(name: String): File? = File(ctx.cacheDir, name).takeIf { it.length() > 1000 }

    @Test fun liveTranscriptWithTheRealSpeechModel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val models = ModelHost(ctx, SettingsStore(ctx), scope)
            val speech = models.speech()
            assumeTrue("OndolSaem has no speech model", speech.value.canTranscribe)
            val wav = fixture("long_talk.wav")
            assumeTrue("fixture missing", wav != null)
            val pcm = File(ctx.cacheDir, "live-src.pcm")
            val duration = AudioDecoder.decode(ctx, Uri.fromFile(wav), pcm)
            val dir = File(ctx.cacheDir, "live").apply { deleteRecursively(); mkdirs() }
            val heard = ArrayList<Cue>()
            val r = Recorder(FileSource(pcm, speed = 2.0), File(dir, "rec.pcm"), File(dir, "rec.m4a"), speech.value, scope, { synchronized(heard) { heard += it } })
            r.start()
            var during = 0
            while (!r.ended) { delay(200); during = synchronized(heard) { heard.size } }
            r.stop()
            speech.close()
            android.util.Log.i("RealModelTest", "duration=$duration during=$during total=${heard.size}: " + heard.joinToString(" | ") { "${it.startMs}: ${it.text}" })
            assertTrue("lines while recording: $during", during >= 2)
            assertTrue(heard.joinToString(" ") { it.text }.length > 100)
            assertTrue(heard.map { it.startMs } == heard.map { it.startMs }.sorted())
        } finally { scope.cancel() }
    }

    /** OndolSaem's real decision model tags Korean notes with the Korean example tags (skipped without the model). */
    @Test fun koreanNotesGetTheRightTags() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, io.github.taehunkim.jogakbo.data.db.AppDatabase::class.java).build()
        try {
            val models = ModelHost(ctx, SettingsStore(ctx), scope)
            models.refresh()
            val decider = models.decider()
            assumeTrue("OndolSaem has no decision model", decider != null)
            val tags = io.github.taehunkim.jogakbo.tags.TagRepository(db.tagDao())
            tags.seed(listOf("업무", "공부", "아이디어", "여행", "건강", "돈", "요리"))
            val repo = io.github.taehunkim.jogakbo.notes.NoteRepository(ctx, db.noteDao(), FakeJobs())
            val expected = mapOf(
                repo.createMemo("제주도 여행 계획", "다음 주 제주도 3박 4일 일정: 첫날 성산일출봉, 둘째 날 한라산 등반. 숙소는 서귀포 게스트하우스.") to "여행",
                repo.createMemo("김치찌개", "돼지고기를 볶고 신김치를 넣어 20분 끓인다. 두부와 대파는 마지막에.") to "요리",
                repo.createMemo("11월 지출", "월세 50만원, 관리비 12만원, 식비 35만원. 적금 30만원 이체.") to "돈",
                repo.createMemo("토익 공부", "RC 파트5 문법 복습, 단어 50개 암기. 모의고사 2회 풀기.") to "공부",
                repo.createMemo("병원", "혈압약 아침 저녁 복용. 다음 진료 12월 3일. 주 3회 걷기 30분.") to "건강",
            )
            val t0 = System.currentTimeMillis()
            io.github.taehunkim.jogakbo.tags.NoteTagger(db.noteDao(), db.tagDao(), { decider }).tagPending()
            val ms = System.currentTimeMillis() - t0
            var right = 0; var extra = 0
            for ((id, tag) in expected) {
                val rows = db.tagDao().ofNotes(listOf(id))
                val yes = rows.filter { it.state.applied }.map { it.name }
                android.util.Log.i("RealModelTest", "$tag: " + rows.joinToString { "${it.name}=${"%.2f".format(it.score ?: 0f)}" })
                if (tag in yes) right++
                extra += (yes - tag).size
            }
            android.util.Log.i("RealModelTest", "tags: $right/${expected.size} right, $extra extra, $ms ms for ${expected.size} notes x 7 tags")
            assertTrue("$right of ${expected.size}", right >= expected.size - 1)
        } finally { db.close(); scope.cancel() }
    }
}
