package com.eve.agent

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentHubLifecycleTest {
    @Test
    fun pipelineStateSurvivesFragmentRecreation() {
        val taskId = "lifecycle-test-task"
        val scenario = launchFragmentInContainer<AgentHubFragment>()

        scenario.onFragment { fragment ->
            val viewModel = ViewModelProvider(fragment.requireActivity())[EveViewModel::class.java]
            viewModel.registerPipeline(taskId, "lifecycle persistence test")
        }

        scenario.recreate()

        scenario.onFragment { fragment ->
            val viewModel = ViewModelProvider(fragment.requireActivity())[EveViewModel::class.java]
            val run = viewModel.pipelineRuns.value.orEmpty().firstOrNull { it.taskId == taskId }
            assertTrue("pipeline run should survive fragment recreation", run != null)
            assertEquals("lifecycle persistence test", run?.task)
            assertEquals("queued", run?.status)
        }

        scenario.close()
    }
}
