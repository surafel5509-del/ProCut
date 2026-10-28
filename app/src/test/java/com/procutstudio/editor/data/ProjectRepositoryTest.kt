package com.procutstudio.editor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.procutstudio.editor.data.local.ProjectDocumentCodec
import com.procutstudio.editor.data.local.db.ProCutDatabase
import com.procutstudio.editor.data.media.ThumbnailStore
import com.procutstudio.editor.data.repository.ProjectRepositoryImpl
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.timeline.TimelineEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for the persistence stack: a real (in-memory) Room database, the real JSON
 * codec and the real repository implementation. It runs on the JVM through Robolectric so it does
 * not need a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectRepositoryTest {

    private lateinit var database: ProCutDatabase
    private lateinit var repository: ProjectRepositoryImpl

    private val second = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ProCutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepositoryImpl(
            dao = database.projectDao(),
            codec = ProjectDocumentCodec(),
            thumbnailStore = ThumbnailStore(context, Dispatchers.Unconfined),
            context = context,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun videoAsset(): MediaAsset = MediaAsset(
        id = "asset-1",
        uri = "file:///nonexistent/clip.mp4",
        displayName = "clip.mp4",
        mediaType = MediaType.VIDEO,
        durationUs = 6 * second,
        width = 1920,
        height = 1080,
        hasAudio = true,
    )

    @Test
    fun `created project can be read back and shows up in the list`() = runBlocking {
        val project = repository.createProject("My first cut", AspectRatio.RATIO_9_16)

        val loaded = repository.getProject(project.id)
        assertNotNull(loaded)
        assertEquals("My first cut", loaded!!.name)
        assertEquals(AspectRatio.RATIO_9_16, loaded.aspectRatio)

        val summaries = repository.observeProjects().first()
        assertEquals(1, summaries.size)
        assertEquals(project.id, summaries.single().id)
        assertEquals("My first cut", summaries.single().name)
    }

    @Test
    fun `saving a project with clips updates the summary columns`() = runBlocking {
        val asset = videoAsset()
        var project = repository.createProject("Timeline", AspectRatio.RATIO_16_9)
        project = TimelineEditor.appendAssets(project, listOf(asset)).touch()
        project = TimelineEditor.addTextOverlay(project, "Hello", startUs = 0L, durationUs = 2 * second)
        repository.saveProject(project)

        val summary = repository.observeProjects().first().single()
        assertEquals(1, summary.clipCount)
        assertEquals(project.durationUs, summary.durationUs)
        assertEquals(AspectRatio.RATIO_16_9, summary.aspectRatio)

        val reloaded = repository.getProject(project.id)
        assertEquals(1, reloaded!!.videoClips.size)
        assertEquals(1, reloaded.textOverlays.size)
        assertEquals("Hello", reloaded.textOverlays.single().text)
    }

    @Test
    fun `rename keeps the document intact`() = runBlocking {
        val project = repository.createProject("Before", AspectRatio.RATIO_1_1)

        val renamed = repository.renameProject(project.id, "After")

        assertEquals("After", renamed!!.name)
        assertEquals(project.id, renamed.id)
        assertEquals(AspectRatio.RATIO_1_1, renamed.aspectRatio)
        assertEquals("After", repository.getProject(project.id)!!.name)
    }

    @Test
    fun `duplicate creates an independent copy`() = runBlocking {
        val project = repository.createProject("Original", AspectRatio.RATIO_4_5)

        val copy = repository.duplicateProject(project.id)

        assertNotNull(copy)
        assertTrue(copy!!.id != project.id)
        assertTrue(copy.name.contains("copy"))
        assertEquals(2, repository.observeProjects().first().size)
        // Editing the copy must not touch the original document.
        repository.saveProject(copy.renamed("Edited copy"))
        assertEquals("Original", repository.getProject(project.id)!!.name)
    }

    @Test
    fun `delete removes the project and its summary`() = runBlocking {
        val project = repository.createProject("Temporary", AspectRatio.RATIO_16_9)

        repository.deleteProject(project.id)

        assertNull(repository.getProject(project.id))
        assertTrue(repository.observeProjects().first().isEmpty())
    }

    @Test
    fun `projects are listed newest first`() = runBlocking {
        val first = repository.createProject("First", AspectRatio.RATIO_16_9)
        val secondProject = repository.createProject("Second", AspectRatio.RATIO_16_9)
        // Pin distinct timestamps so the assertion cannot depend on clock resolution.
        repository.saveProject(first.touch(1_000L))
        repository.saveProject(secondProject.touch(2_000L))

        val ordered = repository.observeProjects().first().map { it.id }

        assertEquals(listOf(secondProject.id, first.id), ordered)
    }

    @Test
    fun `document survives a full round trip through the database`() = runBlocking {
        var project = repository.createProject("Roundtrip", AspectRatio.RATIO_9_16)
        project = TimelineEditor.appendAssets(project, listOf(videoAsset())).touch()
        project = TimelineEditor.setSpeed(project, project.videoClips.single().id, 2f)
        repository.saveProject(project)

        val reloaded = repository.getProject(project.id)!!
        assertEquals(2f, reloaded.videoClips.single().speed, 0.001f)
        assertEquals(project.durationUs, reloaded.durationUs)
    }
}
